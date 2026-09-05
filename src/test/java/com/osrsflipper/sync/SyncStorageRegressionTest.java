package com.osrsflipper.sync;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.Timeout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/** Exercises the real plugin persistence, pairing and HTTP callback paths. */
public class SyncStorageRegressionTest
{
    private static final Gson GSON = new Gson();
    private static final String TOKEN_A = "rlt_" + "a".repeat(48);
    private static final String TOKEN_B = "rlt_" + "b".repeat(48);

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void overflowingHistorySurvivesRestartAndDrainsOneOriginalEventPerRequest() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        try (Harness before = new Harness(root, defaults(), 42))
        {
            for (int index = 0; index < 505; index++)
            {
                before.enqueue(event("event-" + index, index));
            }
            assertEquals(505, before.journal().size());
            assertTrue(before.queue().size() <= 500);
            // The final state can be empty; only the journal retains the intervening trades.
            assertTrue(((Map<?, ?>) get(before.plugin, "slotSnapshots")).isEmpty());
        }
        try (Harness after = new Harness(root, defaults(), 42))
        {
            assertEquals(500, after.queue().size());
            after.enableEventDelivery();
            invoke(after.plugin, "flushOutboxIfPossible");
            for (int index = 0; index < 505; index++)
            {
                TestCall call = after.eventCalls().get(index);
                JsonObject payload = call.payload();
                assertEquals(2, payload.entrySet().size());
                assertTrue(payload.has("event"));
                assertFalse(payload.has("events"));
                assertEquals("event-" + index, payload.getAsJsonObject("event").get("event_id").getAsString());
                assertEquals(index, payload.getAsJsonObject("event").get("filled_quantity").getAsInt());
                call.respond(200, complete("event-" + index));
                after.drain();
                assertTrue(after.queue().size() <= 500);
            }
            assertEquals(505, after.eventCalls().size());
            assertTrue(after.journal().isEmpty());
            assertTrue(after.queue().isEmpty());
        }
        try (Harness reopened = new Harness(root, defaults(), 42))
        {
            assertTrue(reopened.journal().isEmpty());
        }
    }

    @Test
    public void uncertainReplyReplaysExactlyTheSamePayloadAfterRestart() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        String original;
        try (Harness first = new Harness(root, defaults(), 42))
        {
            first.enqueue(event("uncertain", 37));
            first.enableEventDelivery();
            invoke(first.plugin, "flushOutboxIfPossible");
            original = first.eventCalls().get(0).payload().toString();
            first.eventCalls().get(0).fail();
            first.drain();
        }
        try (Harness restarted = new Harness(root, defaults(), 42))
        {
            restarted.enableEventDelivery();
            invoke(restarted.plugin, "flushOutboxIfPossible");
            assertEquals(original, restarted.eventCalls().get(0).payload().toString());
        }
    }

    @Test
    public void legacyImportIsOnceOnlyAndACorruptPriceCacheCannotDiscardEvents() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        Map<String, String> config = defaults();
        List<Object> legacy = new ArrayList<>();
        for (int index = 0; index < 3; index++)
        {
            legacy.add(Collections.singletonMap("event", event("legacy-" + index, index)));
        }
        config.put("outbox_42", GSON.toJson(legacy));
        config.put("lastTradePrices_42", "{broken");
        try (Harness first = new Harness(root, config, 42))
        {
            assertEquals(3, first.journal().size());
            assertTrue((Boolean) invoke(first.plugin, "acknowledgeQueuedEvent", new Class<?>[]{String.class}, "legacy-0"));
        }
        // A stale pre-upgrade config file must never resurrect an acknowledged event.
        config.put("outbox_42", "{now corrupt too");
        try (Harness second = new Harness(root, config, 42))
        {
            assertFalse((Boolean) get(second.plugin, "storageBlocked"));
            assertEquals(2, second.journal().size());
            assertEquals("legacy-1", second.journal().readHead(1).get(0).eventId);
        }
    }

    @Test
    public void pairingAnotherOwnerPreservesOldEventsCashAndPlansWithoutSendingThemWithTheNewToken() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        String oldContext;
        PendingCashUpdate cash = PendingCashUpdate.create(9_000);
        try (Harness harness = new Harness(root, defaults(), 42))
        {
            harness.enqueue(event("alice-history", 40));
            set(harness.plugin, "pendingCashUpdate", cash);
            FlipCyclePlanBook cycles = (FlipCyclePlanBook) get(harness.plugin, "flipCycles");
            cycles.recordBuy("alice-buy", "alice-buy", 1, 4151, "Whip", 1000, 1100, 1020,
                100, 40, "partially_filled", 100, 200);
            Object snapshot = nested("PendingSnapshot");
            set(snapshot, "snapshotId", "alice-snapshot");
            set(harness.plugin, "pendingSnapshot", snapshot);
            invoke(harness.plugin, "persistCurrentAccount");
            oldContext = ((SyncStorageContext) get(harness.plugin, "activeStorageContext")).accountKey;

            // Leave an old event call in flight, then deliver its late response after pairing.
            set(harness.plugin, "pendingSnapshot", null);
            harness.enableEventDelivery();
            invoke(harness.plugin, "flushOutboxIfPossible");
            TestCall oldCall = harness.eventCalls().get(0);
            harness.pair("bob@example.test", "device-b", TOKEN_B);
            assertTrue(oldCall.cancelled);
            assertTrue(harness.queue().isEmpty());
            assertNull(get(harness.plugin, "pendingCashUpdate"));
            assertNull(get(harness.plugin, "pendingSnapshot"));
            assertEquals(0, cycles.size());
            assertEquals(1, new EventJournal(root, oldContext).size());
            oldCall.respond(200, complete("alice-history"));
            harness.drain();
            assertEquals(1, new EventJournal(root, oldContext).size());
            for (TestCall call : harness.eventCalls())
            {
                assertEquals("Bearer " + TOKEN_A, call.request.header("Authorization"));
            }
        }
        try (Harness originalOwner = new Harness(root, defaults(), 42))
        {
            assertEquals("alice-history", originalOwner.journal().readHead(1).get(0).eventId);
            assertEquals(cash.requestId, ((PendingCashUpdate) get(originalOwner.plugin, "pendingCashUpdate")).requestId);
            assertEquals(1, ((FlipCyclePlanBook) get(originalOwner.plugin, "flipCycles")).size());
        }
    }

    @Test
    public void changingOnlyDeviceOriginOrRsAccountAlsoSeparatesAllLocalState() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        try (Harness original = new Harness(root, defaults(), 42))
        {
            original.enqueue(event("private", 1));
            set(original.plugin, "pendingCashUpdate", PendingCashUpdate.create(123));
            invoke(original.plugin, "persistCurrentAccount");
        }
        Map<String, String> newDevice = defaults();
        newDevice.put("deviceId", "device-b");
        Map<String, String> newOrigin = defaults();
        newOrigin.put("webappAddress", "https://other.example.test");
        for (Map<String, String> configuration : List.of(newDevice, newOrigin))
        {
            try (Harness other = new Harness(root, configuration, 42))
            {
                assertTrue(other.journal().isEmpty());
                assertNull(get(other.plugin, "pendingCashUpdate"));
            }
        }
        try (Harness otherRs = new Harness(root, defaults(), 43))
        {
            assertTrue(otherRs.journal().isEmpty());
            assertNull(get(otherRs.plugin, "pendingCashUpdate"));
        }
    }

    @Test
    public void pairingWhileLoggedOutCannotClaimLegacyHistoryOnTheNextLogin() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        Map<String, String> config = defaults();
        config.put("outbox_42", GSON.toJson(List.of(Collections.singletonMap("event", event("old-owner", 1)))));
        try (Harness harness = new Harness(root, config, -1))
        {
            harness.gameState = GameState.LOGIN_SCREEN;
            harness.pair("bob@example.test", "device-b", TOKEN_B);
            harness.accountHash = 42;
            harness.gameState = GameState.LOGGED_IN;
            invoke(harness.plugin, "switchToCurrentAccount");
            assertTrue(harness.journal().isEmpty());
            assertTrue(harness.eventCalls().isEmpty());
            assertTrue(harness.manager.getConfiguration(OsrsFlipperSyncConfig.GROUP, "outbox_42").contains("old-owner"));
        }
        // The original owner/device still owns the migration after a later restart.
        try (Harness original = new Harness(root, config, 42))
        {
            assertEquals("old-owner", original.journal().readHead(1).get(0).eventId);
        }
    }

    @Test
    public void failedDiskOpenRetainsNewEventsAndCashAndRecoversWithoutAnApiCall() throws Exception
    {
        Path root = temporary.newFile().toPath();
        try (Harness harness = new Harness(root, defaults(), 42))
        {
            harness.enqueue(event("waiting-for-disk", 99));
            PendingCashUpdate intent = PendingCashUpdate.create(1234);
            set(harness.plugin, "pendingCashUpdate", intent);
            assertTrue((Boolean) get(harness.plugin, "storageBlocked"));
            assertFalse((Boolean) invoke(harness.plugin, "prepareStorageForDelivery"));
            assertTrue(harness.calls.isEmpty());
            Files.delete(root);
            Files.createDirectories(root);
            set(harness.plugin, "storageRetryAt", 0L);
            assertTrue((Boolean) invoke(harness.plugin, "prepareStorageForDelivery"));
            assertEquals("waiting-for-disk", harness.journal().readHead(1).get(0).eventId);
            assertTrue(harness.calls.isEmpty());
        }
        try (Harness restarted = new Harness(root, defaults(), 42))
        {
            assertEquals(1, restarted.journal().size());
            assertEquals(1234, ((PendingCashUpdate) get(restarted.plugin, "pendingCashUpdate")).balance);
        }
    }

    @Test
    public void cashIsDurableWithItsRequestIdEvenBeforeRsLogin() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        String identity;
        try (Harness harness = new Harness(root, defaults(), -1))
        {
            harness.gameState = GameState.LOGIN_SCREEN;
            invoke(harness.plugin, "setAccountCash", new Class<?>[]{long.class}, 7654L);
            PendingCashUpdate cash = (PendingCashUpdate) get(harness.plugin, "pendingCashUpdate");
            identity = cash.requestId;
            assertTrue(harness.journal().readState().contains(identity));
        }
        try (Harness restarted = new Harness(root, defaults(), -1))
        {
            assertEquals(identity, ((PendingCashUpdate) get(restarted.plugin, "pendingCashUpdate")).requestId);
        }
    }

    @Test
    public void aLateReplyFromARotatedTokenCannotAcknowledgeOrCancelTheNewAttempt() throws Exception
    {
        try (Harness harness = new Harness(temporary.newFolder().toPath(), defaults(), 42))
        {
            harness.enqueue(event("token-rotation", 12));
            harness.enableEventDelivery();
            invoke(harness.plugin, "flushOutboxIfPossible");
            TestCall old = harness.eventCalls().get(0);
            harness.manager.setConfiguration(OsrsFlipperSyncConfig.GROUP, "deviceToken", TOKEN_B);
            old.respond(200, complete("token-rotation"));
            harness.drain();
            assertEquals(1, harness.journal().size());
            TestCall current = harness.eventCalls().get(1);
            assertEquals("Bearer " + TOKEN_B, current.request.header("Authorization"));
            old.respond(200, complete("token-rotation"));
            harness.drain();
            assertFalse(current.cancelled);
            assertEquals(1, harness.journal().size());
            current.respond(200, complete("token-rotation"));
            harness.drain();
            assertTrue(harness.journal().isEmpty());
        }
    }

    @Test
    public void unreadableAccountStateCannotBeOverwrittenOrDeleteTheEventJournal() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        String savedState;
        try (Harness first = new Harness(root, defaults(), 42))
        {
            first.enqueue(event("before-corruption", 1));
            set(first.plugin, "pendingCashUpdate", PendingCashUpdate.create(555));
            invoke(first.plugin, "persistCurrentAccount");
            savedState = first.journal().readState();
            first.journal().writeState("{broken");
        }
        try (Harness recovery = new Harness(root, defaults(), 42))
        {
            assertTrue((Boolean) get(recovery.plugin, "storageBlocked"));
            recovery.enqueue(event("during-recovery", 2));
            invoke(recovery.plugin, "persistCurrentAccount");
            assertEquals("{broken", recovery.journal().readState());
            assertEquals(1, recovery.journal().size());
            recovery.journal().writeState(savedState);
            set(recovery.plugin, "storageRetryAt", 0L);
            assertTrue((Boolean) invoke(recovery.plugin, "prepareStorageForDelivery"));
            assertEquals(2, recovery.journal().size());
            assertEquals(555, ((PendingCashUpdate) get(recovery.plugin, "pendingCashUpdate")).balance);
        }
    }

    @Test
    public void failedAcknowledgementRetainsTheHeadUntilStorageRecovers() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        try (Harness harness = new Harness(root, defaults(), 42))
        {
            harness.enqueue(event("ack-retry", 1));
            Path eventFile;
            try (java.util.stream.Stream<Path> files = Files.walk(root))
            {
                eventFile = files.filter(file -> file.toString().endsWith(".json")).findFirst().orElseThrow();
            }
            String original = Files.readString(eventFile);
            Files.writeString(eventFile, "{corrupt");
            assertFalse((Boolean) invoke(harness.plugin, "acknowledgeQueuedEvent", new Class<?>[]{String.class}, "ack-retry"));
            assertEquals(1, harness.queue().size());
            assertEquals(1, harness.journal().size());
            assertFalse((Boolean) invoke(harness.plugin, "prepareStorageForDelivery"));
            Files.writeString(eventFile, original);
            set(harness.plugin, "storageRetryAt", 0L);
            assertTrue((Boolean) invoke(harness.plugin, "prepareStorageForDelivery"));
            assertTrue((Boolean) invoke(harness.plugin, "acknowledgeQueuedEvent", new Class<?>[]{String.class}, "ack-retry"));
            assertTrue(harness.journal().isEmpty());
        }
    }

    @Test
    public void twoRuneLiteProfilesOnOneComputerMigrateOnlyTheirOwnLegacyHistory() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        Map<String, String> alice = defaults();
        alice.put("outbox_42", GSON.toJson(List.of(Collections.singletonMap("event", event("alice-legacy", 1)))));
        Map<String, String> bob = defaults();
        bob.put("ownerEmail", "bob@example.test");
        bob.put("deviceId", "device-b");
        bob.put("deviceToken", TOKEN_B);
        bob.put("outbox_42", GSON.toJson(List.of(Collections.singletonMap("event", event("bob-legacy", 2)))));
        try (Harness firstProfile = new Harness(root, alice, 42, 1))
        {
            assertEquals("alice-legacy", firstProfile.journal().readHead(1).get(0).eventId);
            firstProfile.pair("bob@example.test", "device-b", TOKEN_B);
            assertTrue(firstProfile.journal().isEmpty());
        }
        try (Harness secondProfile = new Harness(root, bob, 42, 2))
        {
            assertEquals(1, secondProfile.journal().size());
            assertEquals("bob-legacy", secondProfile.journal().readHead(1).get(0).eventId);
            assertTrue((Boolean) invoke(secondProfile.plugin, "acknowledgeQueuedEvent",
                new Class<?>[]{String.class}, "bob-legacy"));
        }
        try (Harness firstProfileAgain = new Harness(root, alice, 42, 1))
        {
            assertEquals("alice-legacy", firstProfileAgain.journal().readHead(1).get(0).eventId);
        }
        try (Harness secondProfileAgain = new Harness(root, bob, 42, 2))
        {
            assertTrue(secondProfileAgain.journal().isEmpty());
        }
    }

    private static String complete(String id)
    {
        return "{\"success\":true,\"summary\":{\"received\":1,\"applied\":1,\"duplicates\":0,\"rejected\":0}," +
            "\"results\":[{\"event_id\":\"" + id + "\",\"outcome\":\"applied\"}]}";
    }

    private static Map<String, String> defaults()
    {
        Map<String, String> configuration = new LinkedHashMap<>();
        configuration.put("webappAddress", "https://storage.example.test");
        configuration.put("deviceToken", TOKEN_A);
        configuration.put("ownerEmail", "alice@example.test");
        configuration.put("deviceId", "device-a");
        return configuration;
    }

    private static Object event(String id, int filled) throws Exception
    {
        Object event = nested("SyncEvent");
        set(event, "eventId", id);
        set(event, "eventSequence", (long) filled + 1);
        set(event, "slotNumber", 1);
        set(event, "offerId", "offer-" + id);
        set(event, "itemId", 4151);
        set(event, "itemName", "Abyssal whip");
        set(event, "side", "sell");
        set(event, "status", "completed");
        set(event, "totalQuantity", 1000);
        set(event, "filledQuantity", filled);
        return event;
    }

    private static final class Harness implements AutoCloseable
    {
        final OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        final List<TestCall> calls = new ArrayList<>();
        final Deque<Runnable> callbacks = new ArrayDeque<>();
        final ConfigManager manager;
        long accountHash;
        GameState gameState = GameState.LOGIN_SCREEN;

        Harness(Path root, Map<String, String> configuration, long accountHash) throws Exception
        {
            this(root, configuration, accountHash, 0L);
        }

        Harness(Path root, Map<String, String> configuration, long accountHash, long profileId) throws Exception
        {
            this.accountHash = accountHash;
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                (proxy, method, args) ->
                {
                    if ("getAccountHash".equals(method.getName())) return this.accountHash;
                    if ("getGameState".equals(method.getName())) return gameState;
                    if ("isClientThread".equals(method.getName())) return true;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
            Constructor<?> constructor = ConfigManager.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            manager = (ConfigManager) constructor.newInstance(null, executor, new EventBus(), client, GSON, null, null, null);
            Constructor<?> profile = Class.forName("net.runelite.client.config.ConfigProfile").getDeclaredConstructor(long.class);
            profile.setAccessible(true);
            set(manager, "profile", profile.newInstance(profileId));
            Constructor<?> data = Class.forName("net.runelite.client.config.ConfigData").getDeclaredConstructor(File.class);
            data.setAccessible(true);
            set(manager, "configProfile", data.newInstance(root.resolveSibling("fixture.properties").toFile()));
            for (Map.Entry<String, String> entry : configuration.entrySet())
            {
                manager.setConfiguration(OsrsFlipperSyncConfig.GROUP, entry.getKey(), entry.getValue());
            }
            set(plugin, "config", new OsrsFlipperSyncConfig()
            {
                private String value(String key) { return manager.getConfiguration(OsrsFlipperSyncConfig.GROUP, key); }
                @Override public String webappAddress() { return value("webappAddress"); }
                @Override public String deviceToken() { return value("deviceToken"); }
                @Override public String ownerEmail() { return value("ownerEmail"); }
                @Override public String deviceId() { return value("deviceId"); }
            });
            set(plugin, "configManager", manager);
            set(plugin, "client", client);
            set(plugin, "gson", GSON);
            set(plugin, "eventJournalRoot", root);
            set(plugin, "clientThread", new ClientThread()
            {
                @Override public void invokeLater(Runnable action) { callbacks.addLast(action); }
            });
            set(plugin, "httpClient", new OkHttpClient()
            {
                @Override public Call newCall(Request request)
                {
                    TestCall call = new TestCall(request);
                    calls.add(call);
                    return call;
                }
            });
            invoke(plugin, "switchToCurrentAccount");
            set(plugin, "started", true);
        }

        void enqueue(Object event) throws Exception
        {
            invoke(plugin, "enqueue", new Class<?>[]{event.getClass()}, event);
        }

        void enableEventDelivery() throws Exception
        {
            gameState = GameState.LOGGED_IN;
            set(plugin, "snapshotPending", false);
            set(plugin, "serverStateCheckPending", false);
            set(plugin, "loginReconciliationPending", false);
        }

        void pair(String owner, String device, String token) throws Exception
        {
            invoke(plugin, "handlePairResponse", new Class<?>[]{int.class, String.class}, 200,
                "{\"device_token\":\"" + token + "\",\"device_id\":\"" + device +
                    "\",\"owner_email\":\"" + owner + "\",\"linked_at\":1234}");
        }

        EventJournal journal() throws Exception { return (EventJournal) get(plugin, "eventJournal"); }
        Deque<?> queue() throws Exception { return (Deque<?>) get(plugin, "outbox"); }
        void drain() { while (!callbacks.isEmpty()) callbacks.removeFirst().run(); }
        List<TestCall> eventCalls()
        {
            return calls.stream().filter(call -> call.request.url().encodedPath().endsWith("/ge-slots/sync"))
                .collect(Collectors.toList());
        }
        @Override public void close() { executor.shutdownNow(); }
    }

    private static final class TestCall implements Call
    {
        final Request request;
        Callback callback;
        boolean cancelled;
        TestCall(Request request) { this.request = request; }
        JsonObject payload() throws IOException
        {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            return GSON.fromJson(buffer.readUtf8(), JsonObject.class);
        }
        void fail() { callback.onFailure(this, new IOException("fixture timeout")); }
        void respond(int status, String json) throws IOException
        {
            callback.onResponse(this, new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("Fixture").body(ResponseBody.create(MediaType.parse("application/json"), json)).build());
        }
        @Override public Request request() { return request; }
        @Override public Response execute() { throw new AssertionError("Real network forbidden"); }
        @Override public void enqueue(Callback callback) { this.callback = callback; }
        @Override public void cancel() { cancelled = true; }
        @Override public boolean isExecuted() { return callback != null; }
        @Override public boolean isCanceled() { return cancelled; }
        @Override public Timeout timeout() { return new Timeout(); }
        @Override public Call clone() { return new TestCall(request); }
    }

    private static Object nested(String name) throws Exception
    {
        Constructor<?> constructor = Class.forName(OsrsFlipperSyncPlugin.class.getName() + "$" + name).getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
    private static Object get(Object target, String name) throws Exception
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
    private static Object invoke(Object target, String name) throws Exception
    {
        return invoke(target, name, new Class<?>[0]);
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception
    {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
