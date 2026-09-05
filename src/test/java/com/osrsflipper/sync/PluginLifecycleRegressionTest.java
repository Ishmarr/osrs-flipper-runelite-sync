package com.osrsflipper.sync;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.callback.ClientThread;
import okhttp3.Call;
import okhttp3.Callback;
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

public class PluginLifecycleRegressionTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void shutdownClosesGateBeforeCancellationAndPersistsOnlyOnClientThread() throws Exception
    {
        Harness harness = new Harness();
        Path root = temporary.newFolder().toPath();
        SyncStorageContext context = SyncStorageContext.capture(new OsrsFlipperSyncConfig() {}, 42L);
        EventJournal journal = new EventJournal(root, context.accountKey);
        PendingCashUpdate cash = PendingCashUpdate.create(12345);
        set(harness.plugin, "eventJournal", journal);
        set(harness.plugin, "activeStorageContext", context);
        set(harness.plugin, "storageInitialized", true);
        set(harness.plugin, "pendingCashUpdate", cash);
        TestCall wiki = harness.fetch(4151);
        AtomicInteger cancellations = new AtomicInteger();
        WorkerRequestCoordinator coordinator = (WorkerRequestCoordinator) get(harness.plugin, "workerRequests");
        coordinator.begin(WorkerRequestCoordinator.Kind.EVENT, new Object(), () ->
        {
            assertFalse("stop must close the gate before cancel invokes callbacks", harness.started());
            cancellations.incrementAndGet();
        });

        SwingUtilities.invokeAndWait(harness.plugin::shutDown);

        assertEquals(1, cancellations.get());
        assertTrue(wiki.isCanceled());
        assertFalse(harness.started());
        assertSame("the EDT must not clear account state", cash, get(harness.plugin, "pendingCashUpdate"));
        assertNull("the EDT must not write the journal", journal.readState());
        harness.drain();
        assertNull(get(harness.plugin, "pendingCashUpdate"));
        assertTrue(journal.readState().contains(cash.requestId));
        assertEquals(1, harness.plugin.uiStops);
    }

    @Test
    public void queuedStartupIsInvalidatedByStopAndNextStartupWaitsForCleanup() throws Exception
    {
        Harness harness = new Harness();
        set(harness.plugin, "started", false);
        PendingCashUpdate cash = PendingCashUpdate.create(99);
        set(harness.plugin, "pendingCashUpdate", cash);
        SwingUtilities.invokeAndWait(() ->
        {
            harness.plugin.startUp();
            harness.plugin.shutDown();
            harness.plugin.startUp();
        });

        assertFalse(harness.started());
        assertSame(cash, get(harness.plugin, "pendingCashUpdate"));
        assertEquals(3, harness.callbacks.size());
        harness.callbacks.removeFirst().run();
        assertSame("obsolete startup must not reset state before shutdown persists it", cash,
            get(harness.plugin, "pendingCashUpdate"));
        harness.callbacks.removeFirst().run();
        assertNull(get(harness.plugin, "pendingCashUpdate"));
        assertEquals("the latest startup remains behind shutdown cleanup", 1, harness.callbacks.size());
        assertEquals(2, harness.plugin.uiStarts);
        assertEquals(1, harness.plugin.uiStops);
        // This harness deliberately has no ConfigManager or real RuneLite profile.
        // A further stop must invalidate the last startup before it can read either.
        SwingUtilities.invokeAndWait(harness.plugin::shutDown);
        harness.drain();
        assertFalse(harness.started());
    }

    @Test
    public void queuedUiActionCannotRunAfterStopOrInTheNextLifecycle() throws Exception
    {
        Harness harness = new Harness();
        AtomicInteger actions = new AtomicInteger();
        harness.plugin.dispatchToClientThread(actions::incrementAndGet);
        SwingUtilities.invokeAndWait(harness.plugin::shutDown);
        harness.drain();
        assertEquals(0, actions.get());

        set(harness.plugin, "started", true);
        harness.plugin.dispatchToClientThread(actions::incrementAndGet);
        set(harness.plugin, "lifecycleGeneration", 99L);
        harness.drain();
        assertEquals(0, actions.get());
        harness.plugin.dispatchToClientThread(actions::incrementAndGet);
        harness.drain();
        assertEquals(1, actions.get());
    }

    @Test
    public void gameEventsAfterStopDoNotAccessClientOrAccountState()
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        // No Client is injected: any stale event reading game state fails the test.
        plugin.onGameTick(new GameTick());
        plugin.onGrandExchangeOfferChanged(new GrandExchangeOfferChanged());
    }

    @Test
    public void lateWikiSuccessAfterShutdownCannotPopulateTheCache() throws Exception
    {
        Harness harness = new Harness();
        TestCall old = harness.fetch(4151);
        old.succeed(4151, 3000);
        SwingUtilities.invokeAndWait(harness.plugin::shutDown);
        harness.drain();
        assertTrue(harness.prices().isEmpty());
        assertFalse((boolean) get(harness.plugin, "marketPriceInFlight"));
    }

    @Test
    public void oldWikiFailureCannotReleaseTheNewRequestAfterRestart() throws Exception
    {
        Harness harness = new Harness();
        TestCall old = harness.fetch(4151);
        SwingUtilities.invokeAndWait(harness.plugin::shutDown);
        harness.drain();
        set(harness.plugin, "started", true);
        TestCall current = harness.fetch(11840);
        old.fail();
        harness.drain();
        assertSame(current, get(harness.plugin, "marketPriceCall"));
        assertTrue((boolean) get(harness.plugin, "marketPriceInFlight"));
        assertEquals(2, harness.calls.size());
        current.succeed(11840, 4000);
        harness.drain();
        assertEquals(4000, harness.prices().get(11840).instantBuyPrice);
        assertFalse((boolean) get(harness.plugin, "marketPriceInFlight"));
    }

    @Test
    public void accountContextCancellationRejectsOldWikiSuccessWithinSameLifecycle() throws Exception
    {
        Harness harness = new Harness();
        TestCall old = harness.fetch(4151);
        invoke(harness.plugin, "invalidateMarketPriceContext");
        TestCall current = harness.fetch(4151);
        assertTrue(old.isCanceled());
        old.succeed(4151, 3000);
        harness.drain();
        assertTrue(harness.prices().isEmpty());
        assertSame(current, get(harness.plugin, "marketPriceCall"));
        assertTrue((boolean) get(harness.plugin, "marketPriceInFlight"));
        current.succeed(4151, 5000);
        harness.drain();
        assertEquals(5000, harness.prices().get(4151).instantBuyPrice);
    }

    private static final class UiProbePlugin extends OsrsFlipperSyncPlugin
    {
        int uiStarts;
        int uiStops;
        @Override void createUi()
        {
            assertTrue("panel creation belongs to the EDT", SwingUtilities.isEventDispatchThread());
            uiStarts++;
        }
        @Override void disposeUi()
        {
            assertTrue("panel disposal belongs to the EDT", SwingUtilities.isEventDispatchThread());
            uiStops++;
        }
    }

    private static final class Harness
    {
        final UiProbePlugin plugin = new UiProbePlugin();
        final Deque<Runnable> callbacks = new ArrayDeque<>();
        final List<TestCall> calls = new ArrayList<>();

        Harness() throws Exception
        {
            set(plugin, "gson", new Gson());
            set(plugin, "config", new OsrsFlipperSyncConfig() {});
            set(plugin, "client", Proxy.newProxyInstance(Client.class.getClassLoader(),
                new Class<?>[]{Client.class}, (proxy, method, arguments) ->
                {
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return -1L;
                    return null;
                }));
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

        TestCall fetch(int itemId) throws Exception
        {
            invoke(plugin, "queueMarketPrice", new Class<?>[]{int.class, boolean.class}, itemId, true);
            invoke(plugin, "flushMarketPriceQueue");
            return calls.get(calls.size() - 1);
        }
        void drain() { while (!callbacks.isEmpty()) callbacks.removeFirst().run(); }
        boolean started()
        {
            try { return (boolean) get(plugin, "started"); }
            catch (Exception exception) { throw new AssertionError(exception); }
        }
        @SuppressWarnings("unchecked") Map<Integer, MarketPriceView> prices() throws Exception
        {
            return (Map<Integer, MarketPriceView>) get(plugin, "marketPrices");
        }
    }

    private static final class TestCall implements Call
    {
        final Request request;
        Callback callback;
        boolean cancelled;
        TestCall(Request request) { this.request = request; }
        void fail() { callback.onFailure(this, new IOException("fixture cancelled or lost response")); }
        void succeed(int itemId, int price) throws IOException
        {
            String body = "{\"data\":{\"" + itemId + "\":{\"high\":" + price +
                ",\"low\":2000,\"highTime\":100,\"lowTime\":100}}}";
            callback.onResponse(this, new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(ResponseBody.create(MediaType.parse("application/json"), body)).build());
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
        Field field = OsrsFlipperSyncPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = OsrsFlipperSyncPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static Object invoke(Object target, String name) throws Exception
    {
        return invoke(target, name, new Class<?>[0]);
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... values) throws Exception
    {
        Method method = OsrsFlipperSyncPlugin.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, values);
    }
}
