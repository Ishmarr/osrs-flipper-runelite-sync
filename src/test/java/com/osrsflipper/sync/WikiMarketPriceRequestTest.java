package com.osrsflipper.sync;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
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

public class WikiMarketPriceRequestTest
{
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void livePriceRequestRevalidatesTheSharedHttpCache()
    {
        Request request = OsrsFlipperSyncPlugin.wikiMarketPriceRequest(12_780);

        assertEquals("12780", request.url().queryParameter("id"));
        assertEquals("no-cache", request.header("Cache-Control"));
    }

    @Test
    public void invalidItemHasNoRequest()
    {
        assertNull(OsrsFlipperSyncPlugin.wikiMarketPriceRequest(0));
    }

    @Test
    public void periodicRefreshQueuesTheFocusedItemWithoutAnOfferSlot() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        Field started = OsrsFlipperSyncPlugin.class.getDeclaredField("started");
        started.setAccessible(true);
        started.setBoolean(plugin, true);
        // Keep transport occupied while exercising the periodic queue producer.
        Field inFlight = OsrsFlipperSyncPlugin.class.getDeclaredField("marketPriceInFlight");
        inFlight.setAccessible(true);
        inFlight.setBoolean(plugin, true);
        Field focusedItem = OsrsFlipperSyncPlugin.class.getDeclaredField("focusedGeItemId");
        focusedItem.setAccessible(true);
        focusedItem.setInt(plugin, 12_780);

        Method refresh = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "requestMarketPrices",
            boolean.class);
        refresh.setAccessible(true);
        refresh.invoke(plugin, false);
        refresh.invoke(plugin, false);

        Field queueField = OsrsFlipperSyncPlugin.class.getDeclaredField("marketPriceQueue");
        queueField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Deque<Integer> queue = (Deque<Integer>) queueField.get(plugin);
        assertEquals(1, queue.size());
        assertEquals(Integer.valueOf(12_780), queue.peekFirst());
    }

    @Test
    public void repeatedPeriodicAndForcedUpdatesShareTheActiveRequestUntilItsCallbackRuns() throws Exception
    {
        Harness h = harness();
        set(h.plugin, "focusedGeItemId", 4151);
        h.refresh(false);
        assertEquals(1, h.calls.size());
        for (int update = 0; update < 20; update++) h.refresh(update % 2 == 0);
        assertEquals(1, h.calls.size());

        h.calls.get(0).succeed(4151, 3000);
        // The HTTP callback is queued, so the result has not reached the
        // client cache yet. That gap must not produce another request either.
        h.refresh(true);
        h.refresh(false);
        assertTrue(h.prices().isEmpty());
        h.drain();
        assertEquals(3000, h.prices().get(4151).instantBuyPrice);
        assertEquals("All waiting consumers use the same newly received price", 1, h.calls.size());
        h.refresh(false);
        assertEquals("The completed price now satisfies the normal cache policy", 1, h.calls.size());
    }

    @Test
    public void aDifferentQueuedItemStillRunsOnceAfterTheActiveItemCompletes() throws Exception
    {
        Harness h = harness();
        h.request(4151, true);
        for (int update = 0; update < 20; update++)
        {
            h.request(4151, update % 2 == 0);
            h.request(11840, update % 2 == 0);
        }
        assertEquals(1, h.calls.size());
        h.calls.get(0).succeed(4151, 3000);
        h.drain();
        assertEquals(2, h.calls.size());
        assertEquals("11840", h.calls.get(1).request.url().queryParameter("id"));
        h.calls.get(1).succeed(11840, 4000);
        h.drain();
        assertEquals(2, h.calls.size());
        assertEquals(2, h.prices().size());
    }

    @Test
    public void anExplicitRefreshAfterCompletionStillBypassesTheFreshLocalCache() throws Exception
    {
        Harness h = harness();
        long now = Instant.now().getEpochSecond();
        h.prices().put(4151, new MarketPriceView(4151, 2000, 1900, now, now, now));
        h.request(4151, false);
        assertTrue(h.calls.isEmpty());
        h.request(4151, true);
        h.request(4151, true);
        assertEquals(1, h.calls.size());
        assertEquals("no-cache", h.calls.get(0).request.header("Cache-Control"));
        h.calls.get(0).succeed(4151, 3000);
        h.drain();
        assertEquals(3000, h.prices().get(4151).instantBuyPrice);

        h.request(4151, true);
        assertEquals("A new explicit refresh is not suppressed by the previous request's identity",
            2, h.calls.size());
        h.calls.get(1).succeed(4151, 4000);
        h.drain();
        assertEquals(4000, h.prices().get(4151).instantBuyPrice);
        assertEquals(2, h.calls.size());
    }

    @Test
    public void failedOrMalformedResponseReleasesItsItemForLaterRecoveryWithoutDuplicateRetry() throws Exception
    {
        for (int failure = 0; failure < 3; failure++)
        {
            Harness h = harness();
            h.request(4151, true);
            h.request(4151, true);
            h.request(11840, true);
            TestCall first = h.calls.get(0);
            if (failure == 0) first.fail();
            else first.respond(failure == 1 ? 503 : 200, "not valid Wiki JSON");
            h.drain();
            assertEquals("Failure advances the other queued item without retrying the same one twice",
                2, h.calls.size());
            assertEquals("11840", h.calls.get(1).request.url().queryParameter("id"));
            assertFalse(h.prices().containsKey(4151));

            set(h.plugin, "focusedGeItemId", 4151);
            h.refresh(false);
            h.refresh(true);
            h.calls.get(1).succeed(11840, 4000);
            h.drain();
            assertEquals(3, h.calls.size());
            assertEquals("4151", h.calls.get(2).request.url().queryParameter("id"));
            h.calls.get(2).succeed(4151, 5000);
            h.drain();
            assertEquals(5000, h.prices().get(4151).instantBuyPrice);
            assertEquals(3, h.calls.size());
        }
    }

    @Test
    public void accountSwitchAllowsTheSameItemAgainAndOldCallbackCannotRemoveItsDeduplication() throws Exception
    {
        Harness h = harness();
        h.request(4151, true);
        h.request(11840, true);
        TestCall old = h.calls.get(0);
        h.accountHash = 84;
        invoke(h.plugin, "switchToCurrentAccount");
        assertTrue(old.cancelled);
        h.request(4151, true);
        assertEquals("The new account can fetch the same item immediately", 2, h.calls.size());
        TestCall current = h.calls.get(1);
        old.succeed(4151, 3000);
        h.drain();
        assertTrue(h.prices().isEmpty());
        h.request(4151, true);
        h.request(4151, false);
        current.succeed(4151, 5000);
        h.drain();
        assertEquals(5000, h.prices().get(4151).instantBuyPrice);
        assertEquals("Neither the old queued item nor duplicate current-item requests survive the switch",
            2, h.calls.size());
    }

    @Test
    public void shutdownDropsQueuedDuplicatesAndLateSuccessCannotStartMoreRequests() throws Exception
    {
        Harness h = harness();
        h.request(4151, true);
        h.request(4151, true);
        h.request(11840, true);
        TestCall old = h.calls.get(0);
        old.succeed(4151, 3000);
        SwingUtilities.invokeAndWait(h.plugin::shutDown);
        assertTrue(old.cancelled);
        h.drain();
        assertTrue(h.prices().isEmpty());
        assertEquals(1, h.calls.size());
        assertEquals(0, get(h.plugin, "marketPriceInFlightItemId"));
        set(h.plugin, "started", true);
        h.request(4151, true);
        assertEquals("Cleanup releases the item's identity for a later lifecycle", 2, h.calls.size());
        h.calls.get(1).succeed(4151, 5000);
        h.drain();
        assertEquals(5000, h.prices().get(4151).instantBuyPrice);
        assertEquals(2, h.calls.size());
    }

    private Harness harness() throws Exception
    {
        return new Harness(temporary.newFolder().toPath());
    }

    private static final class Harness
    {
        final OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin()
        {
            @Override void disposeUi() { assertTrue(SwingUtilities.isEventDispatchThread()); }
        };
        final Deque<Runnable> callbacks = new ArrayDeque<>();
        final List<TestCall> calls = new ArrayList<>();
        long accountHash = 42;

        Harness(Path folder) throws Exception
        {
            set(plugin, "gson", new Gson());
            set(plugin, "config", new OsrsFlipperSyncConfig() {});
            // No ConfigManager or user profile is accessed by this transport
            // fixture. Even incidental account storage stays in this temp root.
            set(plugin, "eventJournalRoot", folder);
            set(plugin, "credentialProfileKey", "default");
            set(plugin, "client", Proxy.newProxyInstance(Client.class.getClassLoader(),
                new Class<?>[]{Client.class}, (proxy, method, arguments) ->
                {
                    if ("getAccountHash".equals(method.getName())) return accountHash;
                    if ("getGameState".equals(method.getName())) return GameState.LOGIN_SCREEN;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
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
                    assertEquals("prices.runescape.wiki", request.url().host());
                    TestCall call = new TestCall(request);
                    calls.add(call);
                    return call;
                }
            });
            invoke(plugin, "switchToCurrentAccount");
            set(plugin, "started", true);
        }

        void refresh(boolean force) throws Exception
        {
            invoke(plugin, "requestMarketPrices", new Class<?>[]{boolean.class}, force);
        }
        void request(int itemId, boolean force) throws Exception
        {
            invoke(plugin, "queueMarketPrice", new Class<?>[]{int.class, boolean.class}, itemId, force);
            invoke(plugin, "flushMarketPriceQueue");
        }
        @SuppressWarnings("unchecked") Map<Integer, MarketPriceView> prices() throws Exception
        {
            return (Map<Integer, MarketPriceView>) get(plugin, "marketPrices");
        }
        void drain()
        {
            int remaining = 100;
            while (!callbacks.isEmpty())
            {
                assertTrue("Callback processing must be bounded", remaining-- > 0);
                callbacks.removeFirst().run();
            }
        }
    }

    private static final class TestCall implements Call
    {
        final Request request;
        Callback callback;
        boolean cancelled;
        TestCall(Request request) { this.request = request; }
        void fail() { callback.onFailure(this, new IOException("Fixture timeout")); }
        void succeed(int itemId, int price) throws IOException
        {
            respond(200, "{\"data\":{\"" + itemId + "\":{\"high\":" + price +
                ",\"low\":2000,\"highTime\":100,\"lowTime\":100}}}");
        }
        void respond(int code, String body) throws IOException
        {
            callback.onResponse(this, new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("Fixture").body(ResponseBody.create(MediaType.parse("application/json"), body)).build());
        }
        @Override public Request request() { return request; }
        @Override public Response execute() { throw new AssertionError("Real network forbidden"); }
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
    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception
    {
        Method method = OsrsFlipperSyncPlugin.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }
}
