package com.osrsflipper.sync;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.Timeout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.*;

public class PairingCredentialSecurityTest
{
    private static final String TOKEN = "rlt_" + "new-credential-fixture".repeat(3);
    private static final String LEGACY_TOKEN = "rlt_" + "legacy-credential-fixture".repeat(3);
    private static final String ADDRESS = "https://pairing.example.test";
    private static final String OWNER = "alice@example.test";
    private static final String DEVICE = "device-a";
    private static final Gson GSON = new Gson();
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void credentialsBindToHttpsOriginProfileOwnerAndDevice()
    {
        PairingCredentials credentials = PairingCredentials.create("0",
            HttpUrl.parse("https://PAIRING.example.test:443/settings?from=runelite#pair"),
            " Alice@Example.Test ", " device-a ", TOKEN);
        assertTrue(credentials.matches("0", HttpUrl.parse(ADDRESS + "/runelite-api/cash"), OWNER, DEVICE));
        assertFalse(credentials.matches("1", HttpUrl.parse(ADDRESS), OWNER, DEVICE));
        assertFalse(credentials.matches("0", HttpUrl.parse("https://other.example.test"), OWNER, DEVICE));
        assertFalse(credentials.matches("0", HttpUrl.parse(ADDRESS + ":444"), OWNER, DEVICE));
        assertFalse(credentials.matches("0", HttpUrl.parse("http://pairing.example.test"), OWNER, DEVICE));
        assertFalse(credentials.matches("0", HttpUrl.parse(ADDRESS), "bob@example.test", DEVICE));
        assertFalse(credentials.matches("0", HttpUrl.parse(ADDRESS), OWNER, "device-b"));
        assertFalse(credentials.matches("0", HttpUrl.parse("https://user:pass@pairing.example.test"), OWNER, DEVICE));
    }

    @Test
    public void invalidOriginOrTokenNeverCreatesCredentials()
    {
        for (String address : new String[]{"http://pairing.example.test", "https://user@pairing.example.test"})
        {
            try
            {
                PairingCredentials.create("0", HttpUrl.parse(address), OWNER, DEVICE, TOKEN);
                fail("Non-HTTPS or credential-bearing URL was accepted");
            }
            catch (IllegalArgumentException expected) { assertFalse(expected.toString().contains(TOKEN)); }
        }
        try
        {
            PairingCredentials.create("0", HttpUrl.parse(ADDRESS), OWNER, DEVICE, "invalid-token");
            fail("Malformed bearer token was accepted");
        }
        catch (IllegalArgumentException expected) { assertFalse(expected.toString().contains("invalid-token")); }
    }

    @Test
    public void credentialStoreRoundTripUsesOwnerOnlyFilePermissions() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        PairingCredentialStore store = new PairingCredentialStore(root);
        assertNull(store.read("0"));
        store.write(credentials("0"));
        assertEquals(TOKEN, store.read("0").token);
        assertOwnerOnly(root, true);
        assertOwnerOnly(root.resolve("0.json"), false);
        store.delete("0");
        assertNull(store.read("0"));
    }

    @Test
    public void corruptCredentialJsonDoesNotExposeTokenInExceptionOrCause() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        PairingCredentialStore store = new PairingCredentialStore(root);
        store.write(credentials("0"));
        Files.writeString(root.resolve("0.json"), "{\"token\":\"" + TOKEN + "\",broken}");
        try
        {
            store.read("0");
            fail("Corrupt credential JSON was accepted");
        }
        catch (IOException expected)
        {
            assertFalse(expected.toString().contains(TOKEN));
            assertNull("Parser causes can contain secrets", expected.getCause());
        }
    }

    @Test
    public void credentialStoreRejectsAnotherProfileAndOversizedFiles() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        PairingCredentialStore store = new PairingCredentialStore(root);
        store.write(credentials("0"));
        Files.writeString(root.resolve("0.json"), GSON.toJson(credentials("1")));
        try { store.read("0"); fail("Wrong profile accepted"); }
        catch (IOException expected) { assertFalse(expected.toString().contains(TOKEN)); }
        Files.writeString(root.resolve("0.json"), " ".repeat(4097));
        try { store.read("0"); fail("Oversized credentials accepted"); }
        catch (IOException expected) { assertFalse(expected.toString().contains(TOKEN)); }
        try { store.read("../0"); fail("Profile path traversal accepted"); }
        catch (IllegalArgumentException expected) { assertFalse(expected.toString().contains(TOKEN)); }
    }

    @Test
    public void legacyTokenWithoutRecordedOriginIsRemovedAndNeverAuthorized() throws Exception
    {
        try (Harness harness = new Harness(temporary.newFolder().toPath()))
        {
            harness.configure("deviceToken", LEGACY_TOKEN);
            invoke(harness.plugin, "initializeCredentials");
            assertNull(harness.manager.getConfiguration(OsrsFlipperSyncConfig.GROUP, "deviceToken"));
            assertFalse((boolean) invoke(harness.plugin, "hasDeviceToken"));
            assertNull(harness.request(ADDRESS).header("Authorization"));
            assertTrue(harness.manager.getConfiguration(OsrsFlipperSyncConfig.GROUP, "connectionStatus")
                .contains("opnieuw"));
            assertFalse(Files.exists(harness.root.resolve("credentials/0.json")));
        }
    }

    @Test
    public void pluginLoadsBoundCredentialsWithoutRestoringConfigToken() throws Exception
    {
        try (Harness harness = new Harness(temporary.newFolder().toPath()))
        {
            new PairingCredentialStore(harness.root.resolve("credentials")).write(credentials("0"));
            invoke(harness.plugin, "initializeCredentials");
            assertTrue((boolean) invoke(harness.plugin, "hasDeviceToken"));
            assertEquals("Bearer " + TOKEN, harness.request(ADDRESS + "/runelite-api/status").header("Authorization"));
            assertNull(harness.manager.getConfiguration(OsrsFlipperSyncConfig.GROUP, "deviceToken"));
        }
    }

    @Test
    public void restartReloadsCredentialsChangedOrRemovedWhileStopped() throws Exception
    {
        try (Harness harness = new Harness(temporary.newFolder().toPath()))
        {
            PairingCredentialStore store = new PairingCredentialStore(harness.root.resolve("credentials"));
            store.write(credentials("0"));
            invoke(harness.plugin, "initializeCredentials");
            assertEquals("Bearer " + TOKEN, harness.request(ADDRESS).header("Authorization"));

            invoke(harness.plugin, "stopOnClientThread");
            store.delete("0");
            invoke(harness.plugin, "initializeCredentials");
            assertNull("a removed token must not survive through the in-memory cache",
                harness.request(ADDRESS).header("Authorization"));

            invoke(harness.plugin, "stopOnClientThread");
            String replacement = "rlt_" + "replacement-credential-fixture".repeat(2);
            store.write(PairingCredentials.create("0", HttpUrl.parse(ADDRESS), OWNER, DEVICE, replacement));
            invoke(harness.plugin, "initializeCredentials");
            assertEquals("Bearer " + replacement, harness.request(ADDRESS).header("Authorization"));
        }
    }

    @Test
    public void pluginNeverSendsBoundBearerToChangedOriginOwnerDeviceOrProfile() throws Exception
    {
        try (Harness harness = new Harness(temporary.newFolder().toPath()))
        {
            set(harness.plugin, "pairingCredentials", credentials("0"));
            assertEquals("Bearer " + TOKEN, harness.request(ADDRESS + "/another/path").header("Authorization"));
            for (String address : new String[]{"https://other.example.test", ADDRESS + ":444", "http://pairing.example.test"})
            {
                Request request = harness.request(address);
                assertNull(request.header("Authorization"));
                set(harness.plugin, "started", true);
                assertNull(invoke(harness.plugin, "beginWorkerRequest",
                    new Class<?>[]{WorkerRequestCoordinator.Kind.class, Request.class},
                    WorkerRequestCoordinator.Kind.STATUS, request));
            }
            assertTrue("rejected origins must not create network calls", harness.calls.isEmpty());
            harness.configure("ownerEmail", "bob@example.test");
            assertNull(harness.request(ADDRESS).header("Authorization"));
            harness.configure("ownerEmail", OWNER);
            harness.configure("deviceId", "device-b");
            assertNull(harness.request(ADDRESS).header("Authorization"));
            harness.configure("deviceId", DEVICE);
            harness.profile(1L);
            assertNull(harness.request(ADDRESS).header("Authorization"));
        }
    }

    @Test
    public void callbackFromPreviousProfileCannotChangeNewProfileEvenWithSameToken() throws Exception
    {
        try (Harness harness = new Harness(temporary.newFolder().toPath()))
        {
            set(harness.plugin, "pairingCredentials", credentials("0"));
            set(harness.plugin, "started", true);
            Call old = (Call) invoke(harness.plugin, "beginWorkerRequest",
                new Class<?>[]{WorkerRequestCoordinator.Kind.class, Request.class},
                WorkerRequestCoordinator.Kind.STATUS, harness.request(ADDRESS + "/runelite-api/status"));
            assertNotNull(old);
            harness.profile(1L);
            set(harness.plugin, "pairingCredentials", credentials("1"));
            AtomicInteger applied = new AtomicInteger();
            invoke(harness.plugin, "finishWorkerRequest",
                new Class<?>[]{WorkerRequestCoordinator.Kind.class, Call.class, Runnable.class},
                WorkerRequestCoordinator.Kind.STATUS, old, (Runnable) applied::incrementAndGet);
            assertEquals(0, applied.get());
            assertFalse(((WorkerRequestCoordinator) get(harness.plugin, "workerRequests")).isActive());
        }
    }

    @Test
    public void newPairingPersistsSecretOnlyInCredentialStoreAndNeverLogsIt() throws Exception
    {
        try (Harness harness = new Harness(temporary.newFolder().toPath()); LogCapture logs = new LogCapture())
        {
            String response = "{\"success\":true,\"device_token\":\"" + TOKEN + "\",\"device_id\":\"" + DEVICE +
                "\",\"owner_email\":\"" + OWNER + "\",\"linked_at\":1234}";
            invoke(harness.plugin, "handlePairResponse", new Class<?>[]{int.class, String.class}, 200, response);
            assertNull(harness.manager.getConfiguration(OsrsFlipperSyncConfig.GROUP, "deviceToken"));
            assertEquals(TOKEN, new PairingCredentialStore(harness.root.resolve("credentials")).read("0").token);
            assertTrue((boolean) invoke(harness.plugin, "hasDeviceToken"));
            invoke(harness.plugin, "handlePairResponse", new Class<?>[]{int.class, String.class}, 200,
                "{\"device_token\":\"" + TOKEN + "\",invalid-json}");
            for (ILoggingEvent event : logs.appender.list)
            {
                assertFalse(event.getFormattedMessage(), event.getFormattedMessage().contains(TOKEN));
                assertNull("pairing parse failures must not log token-bearing exception causes", event.getThrowableProxy());
            }
        }
    }

    private static PairingCredentials credentials(String profile)
    {
        return PairingCredentials.create(profile, HttpUrl.parse(ADDRESS), OWNER, DEVICE, TOKEN);
    }

    private static void assertOwnerOnly(Path path, boolean directory) throws IOException
    {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null)
        {
            assertEquals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"),
                posix.readAttributes().permissions());
        }
        else
        {
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            assertNotNull(acl);
            List<AclEntry> entries = acl.getAcl();
            assertEquals(1, entries.size());
            assertEquals(AclEntryType.ALLOW, entries.get(0).type());
            assertEquals(acl.getOwner(), entries.get(0).principal());
        }
    }

    private static final class LogCapture implements AutoCloseable
    {
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        final Logger pluginLogger = (Logger) LoggerFactory.getLogger(OsrsFlipperSyncPlugin.class);
        final Logger configLogger = (Logger) LoggerFactory.getLogger(ConfigManager.class);
        final Level pluginLevel = pluginLogger.getLevel();
        final Level configLevel = configLogger.getLevel();
        LogCapture()
        {
            appender.start();
            pluginLogger.addAppender(appender);
            configLogger.addAppender(appender);
            pluginLogger.setLevel(Level.DEBUG);
            configLogger.setLevel(Level.DEBUG);
        }
        @Override public void close()
        {
            pluginLogger.detachAppender(appender);
            configLogger.detachAppender(appender);
            pluginLogger.setLevel(pluginLevel);
            configLogger.setLevel(configLevel);
            appender.stop();
        }
    }

    private static final class Harness implements AutoCloseable
    {
        final OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        final ConfigManager manager;
        final Path root;
        final List<Call> calls = new ArrayList<>();
        Harness(Path root) throws Exception
        {
            this.root = root;
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                (proxy, method, args) ->
                {
                    if ("getGameState".equals(method.getName())) return GameState.LOGIN_SCREEN;
                    if ("getAccountHash".equals(method.getName())) return -1L;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
            Constructor<?> constructor = ConfigManager.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            manager = (ConfigManager) constructor.newInstance(null, executor, new EventBus(), client, GSON, null, null, null);
            profile(0L);
            Constructor<?> data = Class.forName("net.runelite.client.config.ConfigData").getDeclaredConstructor(File.class);
            data.setAccessible(true);
            set(manager, "configProfile", data.newInstance(root.resolveSibling("fixture.properties").toFile()));
            configure("webappAddress", ADDRESS);
            configure("ownerEmail", OWNER);
            configure("deviceId", DEVICE);
            set(plugin, "configManager", manager);
            set(plugin, "gson", GSON);
            set(plugin, "client", client);
            set(plugin, "eventJournalRoot", root);
            set(plugin, "config", new OsrsFlipperSyncConfig()
            {
                private String value(String key) { return manager.getConfiguration(OsrsFlipperSyncConfig.GROUP, key); }
                @Override public String webappAddress() { return value("webappAddress"); }
                @Override public String ownerEmail() { return value("ownerEmail"); }
                @Override public String deviceId() { return value("deviceId"); }
                @Override public String deviceToken() { return value("deviceToken"); }
            });
            set(plugin, "httpClient", new OkHttpClient()
            {
                @Override public Call newCall(Request request)
                {
                    Call call = new TestCall(request);
                    calls.add(call);
                    return call;
                }
            });
        }
        void configure(String key, String value) { manager.setConfiguration(OsrsFlipperSyncConfig.GROUP, key, value); }
        void profile(long id) throws Exception
        {
            Constructor<?> profile = Class.forName("net.runelite.client.config.ConfigProfile").getDeclaredConstructor(long.class);
            profile.setAccessible(true);
            set(manager, "profile", profile.newInstance(id));
        }
        Request request(String address) throws Exception
        {
            return ((Request.Builder) invoke(plugin, "authorizedRequest", new Class<?>[]{HttpUrl.class}, HttpUrl.parse(address)))
                .get().build();
        }
        @Override public void close() { executor.shutdownNow(); }
    }

    private static final class TestCall implements Call
    {
        final Request request;
        boolean cancelled;
        TestCall(Request request) { this.request = request; }
        @Override public Request request() { return request; }
        @Override public Response execute() { throw new UnsupportedOperationException(); }
        @Override public void enqueue(Callback callback) { throw new AssertionError("Unexpected network request"); }
        @Override public void cancel() { cancelled = true; }
        @Override public boolean isExecuted() { return false; }
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
        return invoke(target, name, new Class<?>[0]);
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... values) throws Exception
    {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, values);
    }
}
