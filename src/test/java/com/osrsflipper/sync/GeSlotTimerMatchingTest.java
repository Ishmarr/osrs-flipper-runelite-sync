package com.osrsflipper.sync;

import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GeSlotTimerMatchingTest
{
    @Test
    public void acceptsOnlyTheExactLiveOfferInstance() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = pluginWithSnapshot(
            "buy", "active", 4151, 2_000, 100, 1_000, 0);

        GeSlotTimerView matching = plugin.geSlotTimerView(
            0,
            offer(GrandExchangeOfferState.BUYING, 4151, 2_000, 100, 25));

        assertNotNull(matching);
        assertEquals("buy", matching.getSide());
        assertNull(plugin.geSlotTimerView(
            0,
            offer(GrandExchangeOfferState.BUYING, 4151, 2_001, 100, 25)));
        assertNull(plugin.geSlotTimerView(
            1,
            offer(GrandExchangeOfferState.BUYING, 4151, 2_000, 100, 25)));
    }

    @Test
    public void activeTimerUsesMostRecentPersistedFillReset() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = pluginWithSnapshot(
            "sell", "partially_filled", 4151, 2_000, 100,
            1_000, 0, 1_300, 1_300);

        GeSlotTimerView active = plugin.geSlotTimerView(
            0,
            offer(GrandExchangeOfferState.SELLING, 4151, 2_000, 100, 25));

        assertNotNull(active);
        assertEquals("00:00:30", active.timerText(1_330));
    }

    @Test
    public void completedTimerRequiresTheLiveOfferToBeTerminalToo() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = pluginWithSnapshot(
            "buy", "completed", 4151, 2_000, 100,
            1_000, 1_300, 1_300, 1_300);

        assertNull(plugin.geSlotTimerView(
            0,
            offer(GrandExchangeOfferState.BUYING, 4151, 2_000, 100, 100)));
        GeSlotTimerView completed = plugin.geSlotTimerView(
            0,
            offer(GrandExchangeOfferState.BOUGHT, 4151, 2_000, 100, 100));
        assertNotNull(completed);
        assertEquals("00:05:00", completed.timerText(9_999));
    }

    @Test
    public void legacySnapshotFallsBackToOriginalStartedAt() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = pluginWithSnapshot(
            "buy", "active", 4151, 2_000, 100,
            1_000, 0, 0, 0);

        GeSlotTimerView legacy = plugin.geSlotTimerView(
            0,
            offer(GrandExchangeOfferState.BUYING, 4151, 2_000, 100, 0));

        assertNotNull(legacy);
        assertEquals("00:01:00", legacy.timerText(1_060));
    }

    @Test
    public void localAccountJsonAndCopyPreserveTimerFields() throws Exception
    {
        Object snapshot = snapshot(
            "sell", "partially_filled", 4151, 2_000, 100,
            1_000, 0, 1_300, 1_300);
        set(snapshot, "timerFillHighWaterMark", 25);
        Class<?> snapshotType = snapshot.getClass();
        Gson gson = new Gson();

        String json = gson.toJson(snapshot);
        Object restored = gson.fromJson(json, snapshotType);
        Method copyMethod = snapshotType.getDeclaredMethod("copy");
        copyMethod.setAccessible(true);
        Object copied = copyMethod.invoke(restored);

        assertEquals(1_300L, field(restored, "timerStartedAt"));
        assertEquals(1_300L, field(restored, "lastFillAt"));
        assertEquals(1_300L, field(copied, "timerStartedAt"));
        assertEquals(1_300L, field(copied, "lastFillAt"));
        assertEquals(25L, field(restored, "timerFillHighWaterMark"));
        assertEquals(25L, field(copied, "timerFillHighWaterMark"));
    }

    @Test
    public void processOfferUsesWallClockInsteadOfFutureLogicalEventTime() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        set(plugin, "config", new OsrsFlipperSyncConfig()
        {
        });
        long before = Instant.now().getEpochSecond();
        Object snapshot = snapshot(
            "buy", "active", 4151, 2_000, 100,
            before - 100, 0, before - 100, 0);
        set(snapshot, "filledQuantity", 0);
        set(snapshot, "eventSequence", 1L);
        set(snapshot, "lastEventAt", before + 3_600);
        snapshots(plugin).put(1, snapshot);

        Method processOffer = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "processOffer", int.class, GrandExchangeOffer.class, boolean.class);
        processOffer.setAccessible(true);
        processOffer.invoke(
            plugin,
            0,
            offer(GrandExchangeOfferState.BUYING, 4151, 2_000, 100, 10),
            false);

        Object updated = snapshots(plugin).get(1);
        long after = Instant.now().getEpochSecond();
        long timerStartedAt = field(updated, "timerStartedAt");
        assertTrue(timerStartedAt >= before && timerStartedAt <= after);
        assertEquals(timerStartedAt, field(updated, "lastFillAt"));
        assertEquals(10L, field(updated, "timerFillHighWaterMark"));
    }

    @SuppressWarnings("unchecked")
    private static OsrsFlipperSyncPlugin pluginWithSnapshot(
        String side,
        String status,
        int itemId,
        int price,
        int totalQuantity,
        long startedAt,
        long endedAt) throws Exception
    {
        return pluginWithSnapshot(
            side, status, itemId, price, totalQuantity,
            startedAt, endedAt, 0, 0);
    }

    @SuppressWarnings("unchecked")
    private static OsrsFlipperSyncPlugin pluginWithSnapshot(
        String side,
        String status,
        int itemId,
        int price,
        int totalQuantity,
        long startedAt,
        long endedAt,
        long timerStartedAt,
        long lastFillAt) throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        Object snapshot = snapshot(
            side, status, itemId, price, totalQuantity,
            startedAt, endedAt, timerStartedAt, lastFillAt);

        Field snapshotsField = OsrsFlipperSyncPlugin.class.getDeclaredField("slotSnapshots");
        snapshotsField.setAccessible(true);
        ((Map<Integer, Object>) snapshotsField.get(plugin)).put(1, snapshot);
        return plugin;
    }

    private static Object snapshot(
        String side,
        String status,
        int itemId,
        int price,
        int totalQuantity,
        long startedAt,
        long endedAt,
        long timerStartedAt,
        long lastFillAt) throws Exception
    {
        Class<?> snapshotType = Class.forName(
            "com.osrsflipper.sync.OsrsFlipperSyncPlugin$SlotSnapshot");
        Constructor<?> constructor = snapshotType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object snapshot = constructor.newInstance();
        set(snapshot, "slotNumber", 1);
        set(snapshot, "itemId", itemId);
        set(snapshot, "side", side);
        set(snapshot, "status", status);
        set(snapshot, "price", price);
        set(snapshot, "totalQuantity", totalQuantity);
        set(snapshot, "offerId", "timer-offer");
        set(snapshot, "startedAt", startedAt);
        set(snapshot, "endedAt", endedAt);
        set(snapshot, "timerStartedAt", timerStartedAt);
        set(snapshot, "lastFillAt", lastFillAt);
        return snapshot;
    }

    private static long field(Object target, String fieldName) throws Exception
    {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(target);
        return ((Number) value).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Object> snapshots(OsrsFlipperSyncPlugin plugin) throws Exception
    {
        Field field = OsrsFlipperSyncPlugin.class.getDeclaredField("slotSnapshots");
        field.setAccessible(true);
        return (Map<Integer, Object>) field.get(plugin);
    }

    private static void set(Object target, String fieldName, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static GrandExchangeOffer offer(
        GrandExchangeOfferState state,
        int itemId,
        int price,
        int totalQuantity,
        int quantitySold)
    {
        return new GrandExchangeOffer()
        {
            @Override
            public int getQuantitySold()
            {
                return quantitySold;
            }

            @Override
            public int getItemId()
            {
                return itemId;
            }

            @Override
            public int getTotalQuantity()
            {
                return totalQuantity;
            }

            @Override
            public int getPrice()
            {
                return price;
            }

            @Override
            public int getSpent()
            {
                return price * quantitySold;
            }

            @Override
            public GrandExchangeOfferState getState()
            {
                return state;
            }
        };
    }
}
