package com.osrsflipper.sync;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CashUpdateRetryTest
{
    @Test
    public void lostReplyRetryCannotOverwriteCashChangedAfterTheFirstApply() throws Exception
    {
        try (Harness harness = new Harness())
        {
            IdempotentCashServer server = new IdempotentCashServer();
            harness.submit(1_000);
            TestCall first = harness.cashCall(0);
            server.apply(first);
            first.fail();
            harness.drainCallbacks();
            assertTrue((Long) field(harness.plugin, "workerBackoffUntil") > 0);

            // A GE mutation (or the other user's device) changes the committed balance.
            server.balance -= 100;
            harness.retryNow();
            TestCall retry = harness.cashCall(1);
            assertEquals(first.requestId(), retry.requestId());
            server.apply(retry);
            retry.succeed();
            harness.drainCallbacks();

            assertEquals(900, server.balance);
            assertEquals(1, server.appliedIds.size());
            assertNull(harness.pending());
        }
    }

    @Test
    public void oldSuccessCannotConsumeANewerAToBToACommand() throws Exception
    {
        try (Harness harness = new Harness())
        {
            harness.submit(1_000);
            TestCall oldCall = harness.cashCall(0);
            harness.submit(2_000);
            PendingCashUpdate middle = harness.pending();
            harness.submit(1_000);
            PendingCashUpdate newest = harness.pending();
            assertNotEquals(oldCall.requestId(), newest.requestId);
            assertNotEquals(middle.requestId, newest.requestId);

            oldCall.succeed();
            harness.drainCallbacks();
            assertSame(newest, harness.pending());

            // A follow-up overview is independent of the remaining cash command.
            harness.failOverviewIfActive();
            TestCall newCall = harness.cashCall(1);
            assertEquals(newest.requestId, newCall.requestId());
            assertEquals(1_000, newCall.payload().get("cash_balance").getAsLong());

            // Even a stale duplicate callback may not release the newer request.
            oldCall.succeed();
            harness.drainCallbacks();
            assertSame(newest, harness.pending());
            assertTrue((Boolean) field(harness.plugin, "cashInFlight"));
            newCall.succeed();
            harness.drainCallbacks();
            assertNull(harness.pending());
        }
    }

    @Test
    public void restoredIntentUsesItsOriginalRequestIdOnTheNextHttpAttempt() throws Exception
    {
        Gson gson = new Gson();
        String persisted;
        String requestId;
        try (Harness firstSession = new Harness())
        {
            firstSession.submit(7_000);
            TestCall first = firstSession.cashCall(0);
            requestId = first.requestId();
            first.fail();
            firstSession.drainCallbacks();
            persisted = gson.toJson(firstSession.pending());
        }

        PendingCashUpdate restored = gson.fromJson(persisted, PendingCashUpdate.class);
        assertTrue(restored.isValid());
        try (Harness nextSession = new Harness())
        {
            set(nextSession.plugin, "pendingCashUpdate", restored);
            nextSession.retryNow();
            assertEquals(requestId, nextSession.cashCall(0).requestId());
            assertEquals(7_000, nextSession.cashCall(0).payload().get("cash_balance").getAsLong());
        }
    }

    @Test
    public void malformedPersistedCashIntentsAreRecognizableBeforeRecovery()
    {
        Gson gson = new Gson();
        assertFalse(gson.fromJson("{}", PendingCashUpdate.class).isValid());
        assertFalse(gson.fromJson(
            "{\"requestId\":\"invalid\",\"balance\":1000}", PendingCashUpdate.class).isValid());
        PendingCashUpdate valid = PendingCashUpdate.create(1000);
        assertFalse(gson.fromJson(gson.toJson(valid).replace("1000", "-1"),
            PendingCashUpdate.class).isValid());
    }

    private static final class IdempotentCashServer
    {
        private final Set<String> appliedIds = new HashSet<>();
        private long balance;

        void apply(TestCall call) throws IOException
        {
            if (appliedIds.add(call.requestId()))
            {
                balance = call.payload().get("cash_balance").getAsLong();
            }
        }
    }

    private static final class Harness implements AutoCloseable
    {
        private final OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        private final Deque<Runnable> callbacks = new ArrayDeque<>();
        private final List<TestCall> calls = new ArrayList<>();
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        Harness() throws Exception
        {
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
                new Class<?>[]{Client.class}, (proxy, method, arguments) ->
                {
                    if ("getGameState".equals(method.getName())) return GameState.LOGGED_IN;
                    if ("isClientThread".equals(method.getName())) return true;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return -1L;
                    return null;
                });
            Gson gson = new Gson();
            set(plugin, "client", client);
            set(plugin, "gson", gson);
            set(plugin, "config", new OsrsFlipperSyncConfig()
            {
                @Override public String deviceToken() { return "rlt_" + "fixture".repeat(8); }
                @Override public String webappAddress() { return "https://cash-fixture.example.test"; }
            });
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

            // The real ConfigManager stays entirely in memory; its scheduled writer is
            // cancelled when the harness closes, and no RuneLite profile is opened.
            Constructor<?> managerConstructor = ConfigManager.class.getDeclaredConstructors()[0];
            managerConstructor.setAccessible(true);
            ConfigManager manager = (ConfigManager) managerConstructor.newInstance(
                null, executor, new EventBus(), client, gson, null, null, null);
            Class<?> dataClass = Class.forName("net.runelite.client.config.ConfigData");
            Constructor<?> dataConstructor = dataClass.getDeclaredConstructor(File.class);
            dataConstructor.setAccessible(true);
            File unusedFile = new File(System.getProperty("java.io.tmpdir"),
                "cash-test-unused-" + UUID.randomUUID() + ".properties");
            set(manager, "configProfile", dataConstructor.newInstance(unusedFile));
            set(plugin, "configManager", manager);
            set(plugin, "started", true);
        }

        void submit(long value) throws Exception
        {
            invoke(plugin, "setAccountCash", new Class<?>[]{long.class}, value);
        }

        void retryNow() throws Exception
        {
            set(plugin, "workerBackoffUntil", 0L);
            invoke(plugin, "pumpWorkerRequests", new Class<?>[0]);
        }

        void drainCallbacks()
        {
            while (!callbacks.isEmpty()) callbacks.removeFirst().run();
        }

        void failOverviewIfActive()
        {
            TestCall last = calls.get(calls.size() - 1);
            if (last.request.url().encodedPath().endsWith("/overview"))
            {
                last.fail();
                drainCallbacks();
            }
        }

        PendingCashUpdate pending() throws Exception
        {
            return (PendingCashUpdate) field(plugin, "pendingCashUpdate");
        }

        TestCall cashCall(int index)
        {
            return calls.stream().filter(call -> call.request.url().encodedPath().endsWith("/cash"))
                .skip(index).findFirst().orElseThrow(() -> new AssertionError("Cash request missing: " + index));
        }

        @Override public void close() { executor.shutdownNow(); }
    }

    private static final class TestCall implements Call
    {
        private final Request request;
        private Callback callback;
        private boolean cancelled;

        TestCall(Request request) { this.request = request; }
        JsonObject payload() throws IOException
        {
            Buffer body = new Buffer();
            request.body().writeTo(body);
            return new Gson().fromJson(body.readUtf8(), JsonObject.class);
        }
        String requestId() throws IOException { return payload().get("request_id").getAsString(); }
        void fail() { callback.onFailure(this, new IOException("fixture lost reply")); }
        void succeed() throws IOException
        {
            callback.onResponse(this, new Response.Builder().request(request)
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(MediaType.parse("application/json"), "{\"success\":true}"))
                .build());
        }
        @Override public Request request() { return request; }
        @Override public Response execute() { throw new AssertionError("Synchronous network call"); }
        @Override public void enqueue(Callback callback) { this.callback = callback; }
        @Override public void cancel() { cancelled = true; }
        @Override public boolean isExecuted() { return callback != null; }
        @Override public boolean isCanceled() { return cancelled; }
        @Override public Timeout timeout() { return new Timeout(); }
        @Override public Call clone() { return new TestCall(request); }
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

    private static void invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception
    {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(target, arguments);
    }
}
