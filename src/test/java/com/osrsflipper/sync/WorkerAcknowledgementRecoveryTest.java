package com.osrsflipper.sync;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Timeout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class WorkerAcknowledgementRecoveryTest
{
    private static final String ADDRESS = "https://acknowledgement.example.test";
    private static final String OWNER = "owner@example.test";
    private static final String DEVICE = "fixture-device";
    private static final String STATUS = "{\"success\":true,\"server_time\":123," +
        "\"device\":{\"active\":true,\"status\":\"active\",\"device_id\":\"fixture-device\"}," +
        "\"owner\":{\"email\":\"owner@example.test\"}}";
    private static final String HEARTBEAT = "{\"success\":true,\"heartbeat_at\":123," +
        "\"status\":\"active\",\"device_id\":\"fixture-device\",\"owner_email\":\"owner@example.test\"}";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void invalidSuccessfulStatusResponseKeepsPairingAndRetriesWithBackoff() throws Exception
    {
        for (String body : new String[]{"", "<html>upstream error</html>", "{\"success\":true}",
            STATUS.replace(OWNER, "different-owner@example.test")})
        {
            try (Harness harness = new Harness(temporary.newFolder().toPath()))
            {
                set(harness.plugin, "statusCheckPending", true);
                invoke(harness.plugin, "checkDeviceStatus");
                TestCall first = harness.calls.get(0);
                assertTrue(first.request.url().encodedPath().endsWith("/status"));
                first.respond(200, body);
                harness.drain();

                assertTrue(harness.health().failed(SyncHealthTracker.Channel.STATUS));
                assertTrue((boolean) get(harness.plugin, "statusCheckPending"));
                assertEquals(1, get(harness.plugin, "statusRetryAttempts"));
                assertTrue((long) get(harness.plugin, "statusNextAttemptAt") > System.currentTimeMillis() / 1000);
                assertTrue((boolean) invoke(harness.plugin, "hasDeviceToken"));
                assertEquals(OWNER, harness.manager.getConfiguration(OsrsFlipperSyncConfig.GROUP, "ownerEmail"));
                invoke(harness.plugin, "checkDeviceStatus");
                assertEquals("invalid 2xx must not trigger immediate requests", 1, harness.calls.size());

                set(harness.plugin, "workerBackoffUntil", 0L);
                set(harness.plugin, "statusNextAttemptAt", 0L);
                invoke(harness.plugin, "checkDeviceStatus");
                harness.calls.get(1).respond(200, STATUS);
                harness.drain();
                assertFalse(harness.health().failed(SyncHealthTracker.Channel.STATUS));
                assertFalse((boolean) get(harness.plugin, "statusCheckPending"));
                assertEquals(0, get(harness.plugin, "statusRetryAttempts"));
                assertEquals(0L, get(harness.plugin, "statusNextAttemptAt"));
            }
        }
    }

    @Test
    public void invalidSuccessfulHeartbeatCannotClearFailureAndNextValidReplyRecovers() throws Exception
    {
        for (String body : new String[]{"", "<html>upstream error</html>", "{\"success\":true}",
            HEARTBEAT.replace(DEVICE, "another-device")})
        {
            try (Harness harness = new Harness(temporary.newFolder().toPath()))
            {
                invoke(harness.plugin, "sendHeartbeat");
                TestCall first = harness.calls.get(0);
                assertTrue(first.request.url().encodedPath().endsWith("/heartbeat"));
                first.respond(200, body);
                harness.drain();

                assertTrue(harness.health().failed(SyncHealthTracker.Channel.HEARTBEAT));
                assertEquals(1, get(harness.plugin, "heartbeatRetryAttempts"));
                assertTrue((long) get(harness.plugin, "heartbeatNextAttemptAt") > System.currentTimeMillis() / 1000);
                assertTrue((boolean) invoke(harness.plugin, "hasDeviceToken"));
                invoke(harness.plugin, "sendHeartbeat");
                assertEquals("invalid 2xx must not trigger immediate requests", 1, harness.calls.size());

                set(harness.plugin, "workerBackoffUntil", 0L);
                set(harness.plugin, "heartbeatNextAttemptAt", 0L);
                invoke(harness.plugin, "sendHeartbeat");
                harness.calls.get(1).respond(200, HEARTBEAT);
                harness.drain();
                assertFalse(harness.health().failed(SyncHealthTracker.Channel.HEARTBEAT));
                assertEquals(0, get(harness.plugin, "heartbeatRetryAttempts"));
                assertEquals(0L, get(harness.plugin, "heartbeatNextAttemptAt"));
                assertEquals(2, harness.calls.size());
            }
        }
    }

    private static final class Harness implements AutoCloseable
    {
        final OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        final ConfigManager manager;
        final List<TestCall> calls = new ArrayList<>();
        final Deque<Runnable> callbacks = new ArrayDeque<>();
        Harness(Path root) throws Exception
        {
            Gson gson = new Gson();
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                (proxy, method, args) ->
                {
                    if ("getGameState".equals(method.getName())) return GameState.LOGIN_SCREEN;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return -1L;
                    return null;
                });
            Constructor<?> constructor = ConfigManager.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            manager = (ConfigManager) constructor.newInstance(null, executor, new EventBus(), client, gson, null, null, null);
            Constructor<?> data = Class.forName("net.runelite.client.config.ConfigData").getDeclaredConstructor(File.class);
            data.setAccessible(true);
            set(manager, "configProfile", data.newInstance(root.resolve("fixture.properties").toFile()));
            manager.setConfiguration(OsrsFlipperSyncConfig.GROUP, "ownerEmail", OWNER);
            manager.setConfiguration(OsrsFlipperSyncConfig.GROUP, "deviceId", DEVICE);
            set(plugin, "configManager", manager);
            set(plugin, "gson", gson);
            set(plugin, "client", client);
            set(plugin, "eventJournalRoot", root);
            set(plugin, "config", new OsrsFlipperSyncConfig()
            {
                @Override public String webappAddress() { return ADDRESS; }
                @Override public String ownerEmail() { return manager.getConfiguration(OsrsFlipperSyncConfig.GROUP, "ownerEmail"); }
                @Override public String deviceId() { return manager.getConfiguration(OsrsFlipperSyncConfig.GROUP, "deviceId"); }
            });
            set(plugin, "pairingCredentials", PairingCredentials.create("default", HttpUrl.parse(ADDRESS), OWNER, DEVICE,
                "rlt_" + "acknowledgement-fixture".repeat(3)));
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
            set(plugin, "started", true);
        }
        SyncHealthTracker health() throws Exception { return (SyncHealthTracker) get(plugin, "syncHealth"); }
        void drain() { while (!callbacks.isEmpty()) callbacks.removeFirst().run(); }
        @Override public void close() { executor.shutdownNow(); }
    }

    private static final class TestCall implements Call
    {
        final Request request;
        Callback callback;
        boolean cancelled;
        TestCall(Request request) { this.request = request; }
        void respond(int status, String body) throws IOException
        {
            callback.onResponse(this, new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(ResponseBody.create(MediaType.parse("application/json"), body)).build());
        }
        @Override public Request request() { return request; }
        @Override public Response execute() { throw new UnsupportedOperationException(); }
        @Override public void enqueue(Callback callback) { this.callback = callback; }
        @Override public void cancel() { cancelled = true; }
        @Override public boolean isExecuted() { return callback != null; }
        @Override public boolean isCanceled() { return cancelled; }
        @Override public Timeout timeout() { return Timeout.NONE; }
        @Override public Call clone() { return new TestCall(request); }
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
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
