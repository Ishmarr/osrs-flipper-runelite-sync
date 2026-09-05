package com.osrsflipper.sync;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Deque;
import java.util.Map;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SnapshotSchedulingRegressionTest
{
    @Test
    public void completeConflictFreeSnapshotResponseReplacesImmediateStateRequest() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = pluginWaitingForSnapshot("snapshot-complete");

        handleSnapshot(plugin, "snapshot-complete", completeEmptyState(true));

        assertFalse(booleanField(plugin, "serverStateCheckPending"));
        assertFalse(booleanField(plugin, "snapshotPending"));
        assertFalse(booleanField(plugin, "snapshotInFlight"));
        assertNull(field(plugin, "pendingSnapshot"));
        assertEquals(8, slotSnapshots(plugin).size());
    }

    @Test
    public void missingReconcileFlagFailsClosedToStateRequest() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = pluginWaitingForSnapshot("snapshot-untrusted");

        handleSnapshot(plugin, "snapshot-untrusted", completeEmptyState(false));

        assertTrue(booleanField(plugin, "serverStateCheckPending"));
        assertFalse(booleanField(plugin, "snapshotPending"));
    }

    @Test
    public void dirtySnapshotKeepsItsFollowUpWhenResponseNoLongerMatches() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = pluginWaitingForSnapshot("snapshot-dirty");
        set(plugin, "snapshotDirty", true);

        handleSnapshot(plugin, "snapshot-dirty", completeStateWithOccupiedFirstSlot());

        assertTrue(booleanField(plugin, "snapshotPending"));
        assertFalse(booleanField(plugin, "snapshotDirty"));
        assertFalse(booleanField(plugin, "serverStateCheckPending"));
        assertEquals("changed_during_snapshot", field(plugin, "snapshotReason"));
    }

    @Test
    public void conflictedSnapshotAlsoPreservesAChangeObservedDuringTheRequest() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = pluginWaitingForSnapshot("snapshot-conflict");
        set(plugin, "snapshotDirty", true);

        handleSnapshot(
            plugin,
            "snapshot-conflict",
            409,
            "{\"success\":false,\"reconcile_required\":true,\"data\":[]}");

        assertTrue(booleanField(plugin, "snapshotPending"));
        assertFalse(booleanField(plugin, "snapshotDirty"));
        assertTrue(booleanField(plugin, "serverStateCheckPending"));
        assertEquals("changed_during_conflicted_snapshot", field(plugin, "snapshotReason"));
    }

    @Test
    public void temporaryHttpFailureRetainsTheSameDirtySnapshotIntent() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = pluginWaitingForSnapshot("snapshot-retry-http");
        Object pending = field(plugin, "pendingSnapshot");
        set(plugin, "snapshotDirty", true);

        handleSnapshot(plugin, "snapshot-retry-http", 503, "{}");

        assertSame(pending, field(plugin, "pendingSnapshot"));
        assertTrue(booleanField(plugin, "snapshotPending"));
        assertTrue(booleanField(plugin, "snapshotDirty"));
        assertEquals(1, intField(pending, "attempts"));
    }

    @Test
    public void networkFailureRetainsTheSameDirtySnapshotIntent() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = pluginWaitingForSnapshot("snapshot-retry-network");
        Object pending = field(plugin, "pendingSnapshot");
        set(plugin, "snapshotDirty", true);
        Method handle = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "handleSnapshotFailure",
            String.class,
            IOException.class);
        handle.setAccessible(true);

        handle.invoke(plugin, "snapshot-retry-network", new IOException("fixture timeout"));

        assertSame(pending, field(plugin, "pendingSnapshot"));
        assertTrue(booleanField(plugin, "snapshotPending"));
        assertTrue(booleanField(plugin, "snapshotDirty"));
        assertEquals(1, intField(pending, "attempts"));
    }

    @Test
    public void slotChangeDuringSnapshotBackoffCannotAbandonTheDurableIntent() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = pluginWaitingForSnapshot("snapshot-backoff");
        Object pending = field(plugin, "pendingSnapshot");
        set(pending, "attempts", 1);
        set(plugin, "snapshotInFlight", false);
        Object event = newNested("SyncEvent");
        set(event, "eventId", "newer-slot-event");

        Method enqueue = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "enqueue",
            event.getClass());
        enqueue.setAccessible(true);
        enqueue.invoke(plugin, event);

        assertSame(pending, field(plugin, "pendingSnapshot"));
        assertTrue(booleanField(plugin, "snapshotPending"));
        assertTrue(booleanField(plugin, "snapshotDirty"));
        assertEquals(1, outbox(plugin).size());
    }

    @Test
    public void boundedWorkerSnapshotContinuationIsProgressNotAHealthFailure() throws Exception
    {
        for (int status : new int[]{202, 503})
        {
            String snapshotId = "snapshot-continuation-" + status;
            OsrsFlipperSyncPlugin plugin = pluginWaitingForSnapshot(snapshotId);
            Object pending = field(plugin, "pendingSnapshot");

            handleSnapshot(
                plugin,
                snapshotId,
                status,
                "{\"success\":false,\"code\":\"snapshot_processing\"," +
                    "\"reconcile_required\":false}");

            assertSame(pending, field(plugin, "pendingSnapshot"));
            assertTrue(booleanField(plugin, "snapshotPending"));
            assertFalse(booleanField(plugin, "snapshotInFlight"));
            assertEquals(0, intField(pending, "attempts"));
            assertTrue(longField(pending, "nextAttemptAt") > 0L);
            assertEquals(0L, longField(plugin, "workerBackoffUntil"));
            assertEquals("", ((SyncHealthTracker) field(plugin, "syncHealth")).banner(0));
        }
    }

    @Test
    public void newSnapshotTriggerDuringBackoffQueuesAFollowUpWithoutReplacingTheIntent()
        throws Exception
    {
        OsrsFlipperSyncPlugin plugin = pluginWaitingForSnapshot("snapshot-trigger-backoff");
        Object pending = field(plugin, "pendingSnapshot");
        set(plugin, "snapshotInFlight", false);

        Method queue = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "queueFullSnapshot",
            String.class);
        queue.setAccessible(true);
        queue.invoke(plugin, "manual_sync");

        assertSame(pending, field(plugin, "pendingSnapshot"));
        assertTrue(booleanField(plugin, "snapshotPending"));
        assertTrue(booleanField(plugin, "snapshotDirty"));
    }

    @Test
    public void authoritativeRuneLiteScanRequiresEightNonNullOffers() throws Exception
    {
        Method complete = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "hasCompleteRuneLiteSlotArray",
            GrandExchangeOffer[].class);
        complete.setAccessible(true);

        GrandExchangeOffer[] valid = new GrandExchangeOffer[8];
        for (int slot = 0; slot < valid.length; slot++)
        {
            valid[slot] = emptyOffer();
        }
        assertTrue((Boolean) complete.invoke(null, (Object) valid));
        assertFalse((Boolean) complete.invoke(null, (Object) new GrandExchangeOffer[7]));
        assertFalse((Boolean) complete.invoke(null, (Object) new GrandExchangeOffer[9]));
        valid[4] = null;
        assertFalse((Boolean) complete.invoke(null, (Object) valid));
        assertFalse((Boolean) complete.invoke(null, new Object[]{null}));
    }

    @Test
    public void unsuccessfulStateResponseCannotBeAcceptedAsAuthoritative() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = pluginWaitingForSnapshot("unused");
        set(plugin, "slotStateInFlight", true);
        set(plugin, "serverStateCheckPending", false);
        Method handle = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "handleServerStateResponse",
            int.class,
            String.class);
        handle.setAccessible(true);

        handle.invoke(
            plugin,
            200,
            completeEmptyState(true).replace("\"success\":true", "\"success\":false"));

        assertTrue(booleanField(plugin, "serverStateCheckPending"));
        assertFalse(booleanField(plugin, "slotStateInFlight"));
        assertEquals(1, intField(plugin, "serverStateRetryAttempts"));
    }

    @Test
    public void pluginWiresEveryTriggerToTheIntendedSnapshotMode() throws Exception
    {
        String source = new String(Files.readAllBytes(Paths.get(
            "src/main/java/com/osrsflipper/sync/OsrsFlipperSyncPlugin.java")), StandardCharsets.UTF_8);
        String compact = source.replaceAll("\\s+", " ");

        assertTrue(compact.contains(
            "\"ge_open\", SnapshotSyncPolicy.ReconcileMode.WHEN_CHANGED"));
        assertTrue(compact.contains(
            "\"periodic_reconcile\", SnapshotSyncPolicy.ReconcileMode.NEVER"));
        assertTrue(compact.contains(
            "\"periodic_hourly\", SnapshotSyncPolicy.ReconcileMode.ALWAYS"));
        assertTrue(compact.contains(
            "requestInFlight || loginReconciliationPending || " +
                "(hasQueuedEvents() && !continuingDurableSnapshot)"));
        assertTrue(compact.contains(
            "anyWorkerRequestInFlight() || loginReconciliationPending || hasQueuedEvents()"));
    }

    private static OsrsFlipperSyncPlugin pluginWaitingForSnapshot(String snapshotId) throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        set(plugin, "gson", new Gson());
        set(plugin, "config", new OsrsFlipperSyncConfig()
        {
        });
        Object pending = newNested("PendingSnapshot");
        set(pending, "snapshotId", snapshotId);
        set(pending, "reason", "test");
        set(plugin, "pendingSnapshot", pending);
        set(plugin, "snapshotPending", true);
        set(plugin, "snapshotInFlight", true);
        set(plugin, "serverStateCheckPending", true);
        return plugin;
    }

    private static void handleSnapshot(
        OsrsFlipperSyncPlugin plugin,
        String snapshotId,
        String response) throws Exception
    {
        handleSnapshot(plugin, snapshotId, 200, response);
    }

    private static void handleSnapshot(
        OsrsFlipperSyncPlugin plugin,
        String snapshotId,
        int status,
        String response) throws Exception
    {
        Method handle = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "handleSnapshotResponse",
            String.class,
            int.class,
            String.class);
        handle.setAccessible(true);
        handle.invoke(plugin, snapshotId, status, response);
    }

    private static String completeEmptyState(boolean includeReconcileFlag)
    {
        StringBuilder json = new StringBuilder("{\"success\":true");
        if (includeReconcileFlag)
        {
            json.append(",\"reconcile_required\":false");
        }
        json.append(",\"data\":[");
        for (int slot = 1; slot <= 8; slot++)
        {
            if (slot > 1)
            {
                json.append(',');
            }
            json.append("{\"slot_number\":")
                .append(slot)
                .append(",\"status\":\"empty\",\"version\":")
                .append(slot)
                .append('}');
        }
        return json.append("]}").toString();
    }

    private static String completeStateWithOccupiedFirstSlot()
    {
        StringBuilder json = new StringBuilder(
            "{\"success\":true,\"reconcile_required\":false,\"data\":[" +
                "{\"slot_number\":1,\"status\":\"active\",\"item_id\":4151," +
                "\"side\":\"buy\",\"price\":1000,\"total_quantity\":1," +
                "\"filled_quantity\":0,\"spent_amount\":0}");
        for (int slot = 2; slot <= 8; slot++)
        {
            json.append(",{\"slot_number\":")
                .append(slot)
                .append(",\"status\":\"empty\"}");
        }
        return json.append("]}").toString();
    }

    private static GrandExchangeOffer emptyOffer()
    {
        return new GrandExchangeOffer()
        {
            @Override
            public int getQuantitySold()
            {
                return 0;
            }

            @Override
            public int getItemId()
            {
                return 0;
            }

            @Override
            public int getTotalQuantity()
            {
                return 0;
            }

            @Override
            public int getPrice()
            {
                return 0;
            }

            @Override
            public int getSpent()
            {
                return 0;
            }

            @Override
            public GrandExchangeOfferState getState()
            {
                return GrandExchangeOfferState.EMPTY;
            }
        };
    }

    private static Object newNested(String simpleName) throws Exception
    {
        Class<?> type = Class.forName(
            "com.osrsflipper.sync.OsrsFlipperSyncPlugin$" + simpleName);
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Object> slotSnapshots(OsrsFlipperSyncPlugin plugin) throws Exception
    {
        return (Map<Integer, Object>) field(plugin, "slotSnapshots");
    }

    @SuppressWarnings("unchecked")
    private static Deque<Object> outbox(OsrsFlipperSyncPlugin plugin) throws Exception
    {
        return (Deque<Object>) field(plugin, "outbox");
    }

    private static boolean booleanField(Object target, String name) throws Exception
    {
        return (Boolean) field(target, name);
    }

    private static int intField(Object target, String name) throws Exception
    {
        return (Integer) field(target, name);
    }

    private static long longField(Object target, String name) throws Exception
    {
        return (Long) field(target, name);
    }

    private static Object field(Object target, String name) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
