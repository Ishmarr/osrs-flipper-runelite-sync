package com.osrsflipper.sync;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
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
import okio.Buffer;
import okio.Timeout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/** Exercises actual request creation and queued HTTP callbacks, with no network. */
public class OverviewListContinuityTest
{
    private static final Gson GSON = new Gson();
    private static final long NOW = Instant.now().getEpochSecond();
    private static final int FOCUS = 1444;
    private static final List<Integer> TOP_IDS = Arrays.asList(1001, 1002, 1003, 1004, 1005);

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void focusedRingCallbackRepricesOldZeroAndSharesTheSafeQuantityWithPanelAndEditor() throws Exception
    {
        try (Harness h = harness())
        {
            h.attachPanel();
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW, false, true, 100_000_000));
            h.drain();
            h.focus(6737);
            JsonObject response = GSON.fromJson(payload(new ArrayList<>(), 6737, NOW, false, true, 100_000_000), JsonObject.class);
            JsonObject row = response.getAsJsonObject("opportunities").getAsJsonObject("focus");
            row.addProperty("item_name", "Berserker ring");
            row.addProperty("buy_price", 3_900_000);
            row.addProperty("sell_price", 3_913_491);
            row.addProperty("instant_buy", 3_913_492);
            row.addProperty("instant_sell", 3_899_999);
            row.addProperty("maximum_quantity", 0);
            row.addProperty("maximum_cycle_profit", 0);
            row.addProperty("official_buy_limit", 8);
            row.addProperty("used_buy_limit", 0);
            row.addProperty("remaining_buy_limit", 8);
            row.add("quantity_capacity", GSON.fromJson("{\"cash_available\":0,\"buy_volume_per_hour\":34," +
                "\"sell_volume_per_hour\":35,\"guide_price\":3942506}", JsonObject.class));
            h.requestFocus(6737).respond(GSON.toJson(response));
            h.drain();
            int requestCount = h.calls.size();
            invoke(h.plugin, "handleMarketPriceResponse", new Class<?>[]{int.class, int.class, String.class},
                6737, 200, "{\"data\":{\"6737\":{\"high\":3913492,\"low\":3804267," +
                    "\"highTime\":" + NOW + ",\"lowTime\":" + NOW + "}}}");
            assertEquals(8, invoke(h.plugin, "currentGeQuantity", new Class<?>[]{int.class}, 6737));
            String text = h.renderedPanelText();
            assertTrue(text, text.contains("Berserker ring"));
            assertTrue(text, text.contains("247 632 GP"));
            assertFalse(text, text.contains("Geen uitvoerbaar aantal"));
            assertEquals("Repricing reuses already fetched resources", requestCount, h.calls.size());
            RuneliteOverviewView current = h.view();
            set(h.plugin, "overview", current.withCash(new RuneliteOverviewView.CashBalance(3_804_267, 0, 3_804_267, NOW)));
            assertEquals(0, invoke(h.plugin, "currentGeQuantity", new Class<?>[]{int.class}, 6737));
            invoke(h.plugin, "refreshSidePanel");
            assertTrue(h.renderedPanelText().contains("Onvoldoende beschikbare cash"));
            assertEquals(requestCount, h.calls.size());
        }
    }

    @Test
    public void cashAcknowledgementUpdatesPanelBeforeOverviewAndOlderSnapshotsCannotUndoIt() throws Exception
    {
        try (Harness h = harness())
        {
            h.attachPanel();
            h.requestFull(true).respond(versionedCashPayload(900, 4, NOW * 1000));
            h.drain();
            invoke(h.plugin, "setAccountCash", new Class<?>[]{long.class}, 1000L);
            TestCall cash = h.calls.get(h.calls.size() - 1);
            assertTrue(cash.request.url().encodedPath().endsWith("/cash"));
            cash.respond("{\"success\":true,\"cash\":{\"available\":1000,\"reserved\":200," +
                "\"available_plus_reserved\":1200,\"version\":5,\"updated_at\":" + NOW +
                ",\"snapshot_at_ms\":" + (NOW * 1000 + 20) + "}}");
            h.drain();
            assertEquals(1000, h.view().cash.available);
            assertEquals(5, h.view().cash.version);
            SwingUtilities.invokeAndWait(() -> {
                try { assertEquals("1 000 GP", ((JLabel) get(h.panel, "cashAvailable")).getText()); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            TestCall refresh = h.calls.get(h.calls.size() - 1);
            assertTrue(refresh.request.url().encodedPath().endsWith("/overview"));
            refresh.respond(versionedCashPayload(900, 4, NOW * 1000 + 100));
            h.drain();
            assertEquals(1000, h.view().cash.available);

            h.focus(FOCUS);
            h.requestFocus(FOCUS).respond(versionedCashPayload(800, 4, NOW * 1000 + 200));
            h.drain();
            assertEquals("A focused reply also cannot revert committed cash", 1000, h.view().cash.available);
            h.closeFocus();
            h.requestFull(true).respond(versionedCashPayload(700, 5, NOW * 1000));
            h.drain();
            assertEquals("An earlier read of the same version is stale", 1000, h.view().cash.available);
            h.requestFull(true).respond(versionedCashPayload(1200, 6, NOW * 1000 + 300));
            h.drain();
            assertEquals(1200, h.view().cash.available);
            assertEquals("One PUT and four full plus one focused overview", 6, h.calls.size());
        }
    }

    @Test
    public void legacyCashWorksUntilVersionedCashArrivesAndAccountSwitchResetsTheVersion() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW, false, true, 500));
            h.drain();
            assertEquals(500, h.view().cash.available);
            h.requestFull(true).respond(versionedCashPayload(1000, 100, NOW * 1000));
            h.drain();
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW, false, true, 600));
            h.drain();
            assertEquals("Unversioned cached data cannot erase a confirmed version", 1000, h.view().cash.available);
            h.accountHash = 84;
            invoke(h.plugin, "switchToCurrentAccount");
            h.disableUnrelatedScheduling();
            h.requestFull(true).respond(versionedCashPayload(200, 1, NOW * 1000));
            h.drain();
            assertEquals(200, h.view().cash.available);
            assertEquals(1, h.view().cash.version);
        }
    }

    @Test
    public void invalidOverviewCashCannotReplaceTheLastGoodBalanceOrPanel() throws Exception
    {
        for (String invalidCash : new String[] {
            "{\"available\":100,\"reserved\":-1,\"available_plus_reserved\":99,\"updated_at\":1}",
            "{\"available\":100,\"reserved\":1,\"available_plus_reserved\":102,\"updated_at\":1}",
            "{\"available\":9007199254740992,\"reserved\":0,\"available_plus_reserved\":9007199254740992,\"updated_at\":1}",
            "{\"available\":\"100\",\"reserved\":0,\"available_plus_reserved\":100,\"updated_at\":1}",
            "{\"available\":100,\"reserved\":0,\"available_plus_reserved\":100,\"updated_at\":1,\"version\":-1}"
        })
        {
            try (Harness h = harness())
            {
                h.attachPanel();
                h.requestFull(true).respond(versionedCashPayload(1000, 5, NOW * 1000));
                h.drain();
                JsonObject broken = GSON.fromJson(versionedCashPayload(100, 6, NOW * 1000), JsonObject.class);
                broken.add("cash", GSON.fromJson(invalidCash, JsonObject.class));
                h.requestFull(true).respond(GSON.toJson(broken));
                h.drain();
                assertEquals(1000, h.view().cash.available);
                assertEquals(5, h.view().cash.version);
                assertTrue(h.health().failed(SyncHealthTracker.Channel.OVERVIEW));
                assertEquals("Malformed personal data must not create a retry storm", 2, h.calls.size());
            }
        }
    }

    @Test
    public void signedReconciliationBalanceRemainsVisibleInsteadOfBecomingZero() throws Exception
    {
        try (Harness h = harness())
        {
            h.attachPanel();
            h.requestFull(true).respond(versionedCashPayload(-100, 5, NOW * 1000));
            h.drain();
            assertEquals(-100, h.view().cash.available);
            assertEquals(-100, h.view().cash.total);
            SwingUtilities.invokeAndWait(() -> {
                try { assertEquals("-100 GP", ((JLabel) get(h.panel, "cashAvailable")).getText()); }
                catch (Exception error) { throw new AssertionError(error); }
            });
        }
    }

    @Test
    public void yesterdayListingSoldTodayKeepsItsStartButSendsTodaysRealizationAndRefreshesStats() throws Exception
    {
        try (Harness h = harness())
        {
            h.attachPanel();
            h.enableGameTicks();
            long dayStart = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
            long yesterday = dayStart - 3600;
            Class<?> snapshotType = Class.forName(OsrsFlipperSyncPlugin.class.getName() + "$SlotSnapshot");
            Object previous = GSON.fromJson("{\"slotNumber\":1,\"itemId\":4151,\"itemName\":\"Whip\"," +
                "\"side\":\"sell\",\"price\":1200,\"totalQuantity\":10,\"filledQuantity\":0," +
                "\"status\":\"active\",\"offerId\":\"overnight-sell\",\"startedAt\":" + yesterday +
                ",\"lastEventAt\":" + yesterday + ",\"eventSequence\":1}", snapshotType);
            ((Map<Integer, Object>) get(h.plugin, "slotSnapshots")).put(1, previous);
            GrandExchangeOffer offer = (GrandExchangeOffer) Proxy.newProxyInstance(
                GrandExchangeOffer.class.getClassLoader(), new Class<?>[]{GrandExchangeOffer.class},
                (proxy, method, arguments) -> {
                    switch (method.getName())
                    {
                        case "getState": return GrandExchangeOfferState.SOLD;
                        case "getItemId": return 4151;
                        case "getPrice": return 1200;
                        case "getTotalQuantity": case "getQuantitySold": return 10;
                        case "getSpent": return 12000;
                        default: return primitiveDefault(method.getReturnType());
                    }
                });
            GrandExchangeOfferChanged changed = new GrandExchangeOfferChanged();
            changed.setSlot(0);
            changed.setOffer(offer);
            h.plugin.onGrandExchangeOfferChanged(changed);
            TestCall sell = h.calls.get(h.calls.size() - 1);
            Buffer buffer = new Buffer();
            sell.request.body().writeTo(buffer);
            JsonObject event = GSON.fromJson(buffer.readUtf8(), JsonObject.class).getAsJsonObject("event");
            assertEquals("sell_completed", event.get("event_type").getAsString());
            assertEquals(yesterday, event.get("started_at").getAsLong());
            assertTrue(event.get("ended_at").getAsLong() >= dayStart);
            assertTrue(event.get("event_at").getAsLong() >= dayStart);
            String eventId = event.get("event_id").getAsString();
            sell.respond("{\"success\":true,\"summary\":{\"received\":1,\"applied\":1,\"duplicate\":0,\"rejected\":0}," +
                "\"results\":[{\"event_id\":\"" + eventId + "\",\"outcome\":\"applied\",\"classification\":\"sell_completed\"}]}");
            h.drain();
            TestCall refresh = h.overviewCalls().get(0);
            assertEquals(dayStart, Long.parseLong(refresh.request.url().queryParameter("day_start")));
            JsonObject response = GSON.fromJson(versionedCashPayload(12000, 2, NOW * 1000), JsonObject.class);
            JsonObject today = response.getAsJsonObject("stats").getAsJsonObject("today");
            today.addProperty("realized_profit", 1760);
            today.addProperty("completed_flips", 1);
            refresh.respond(GSON.toJson(response));
            h.drain();
            SwingUtilities.invokeAndWait(() -> {
                try { assertEquals("+1 760 GP", ((JLabel) get(h.panel, "statsProfit")).getText()); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            today.addProperty("attribution_complete", false);
            today.addProperty("attribution_error", "Verkoopmoment ontbreekt voor oudere verkopen");
            h.requestFull(true).respond(GSON.toJson(response));
            h.drain();
            assertFalse(h.view().today.attributionComplete);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    assertEquals("—", ((JLabel) get(h.panel, "statsProfit")).getText());
                    assertTrue(componentText((Container) get(h.panel, "statsItemsList")).contains("Verkoopmoment ontbreekt"));
                }
                catch (Exception error) { throw new AssertionError(error); }
            });
        }
    }

    private static String versionedCashPayload(long cash, long version, long snapshotAtMs)
    {
        JsonObject response = GSON.fromJson(payload(TOP_IDS, 0, NOW, false, true, cash), JsonObject.class);
        response.getAsJsonObject("cash").addProperty("version", version);
        response.getAsJsonObject("cash").addProperty("snapshot_at_ms", snapshotAtMs);
        return GSON.toJson(response);
    }

    @Test
    public void logoutEmptyEventsCannotCloseAnOfferOrGiveItsCumulativeFillANewIdentity() throws Exception
    {
        try (Harness h = harness())
        {
            h.enableGameTicks();
            Class<?> snapshotType = Class.forName(OsrsFlipperSyncPlugin.class.getName() + "$SlotSnapshot");
            Object previous = GSON.fromJson("{\"slotNumber\":1,\"itemId\":4151,\"itemName\":\"Whip\"," +
                "\"side\":\"sell\",\"price\":1200,\"totalQuantity\":10,\"filledQuantity\":4,\"spentAmount\":4800," +
                "\"status\":\"partially_filled\",\"offerId\":\"survives-logout\",\"startedAt\":" + (NOW - 86400) +
                ",\"lastEventAt\":" + (NOW - 100) + ",\"eventSequence\":2}", snapshotType);
            ((Map<Integer, Object>) get(h.plugin, "slotSnapshots")).put(1, previous);
            h.gameState = GameState.LOGIN_SCREEN;
            GameStateChanged logout = new GameStateChanged();
            logout.setGameState(GameState.LOGIN_SCREEN);
            h.plugin.onGameStateChanged(logout);
            GrandExchangeOfferChanged changed = new GrandExchangeOfferChanged();
            changed.setSlot(0);
            changed.setOffer((GrandExchangeOffer) Proxy.newProxyInstance(
                GrandExchangeOffer.class.getClassLoader(), new Class<?>[]{GrandExchangeOffer.class},
                (proxy, method, arguments) -> "getState".equals(method.getName())
                    ? GrandExchangeOfferState.EMPTY : primitiveDefault(method.getReturnType())));
            h.plugin.onGrandExchangeOfferChanged(changed);
            Object current = ((Map<?, ?>) get(h.plugin, "slotSnapshots")).get(1);
            assertEquals("partially_filled", get(current, "status"));
            assertEquals("survives-logout", get(current, "offerId"));
            assertTrue("Logout must not invent a cash/refund event", h.calls.isEmpty());

            h.gameState = GameState.LOGGED_IN;
            set(h.plugin, "loginReconciliationPending", false);
            changed.setOffer((GrandExchangeOffer) Proxy.newProxyInstance(
                GrandExchangeOffer.class.getClassLoader(), new Class<?>[]{GrandExchangeOffer.class},
                (proxy, method, arguments) -> {
                    switch (method.getName())
                    {
                        case "getState": return GrandExchangeOfferState.SELLING;
                        case "getItemId": return 4151;
                        case "getPrice": return 1200;
                        case "getTotalQuantity": return 10;
                        case "getQuantitySold": return 6;
                        case "getSpent": return 7200;
                        default: return primitiveDefault(method.getReturnType());
                    }
                }));
            h.plugin.onGrandExchangeOfferChanged(changed);
            TestCall fill = h.calls.get(h.calls.size() - 1);
            Buffer buffer = new Buffer();
            fill.request.body().writeTo(buffer);
            JsonObject event = GSON.fromJson(buffer.readUtf8(), JsonObject.class).getAsJsonObject("event");
            assertEquals("survives-logout", event.get("runelite_offer_id").getAsString());
            assertEquals(NOW - 86400, event.get("started_at").getAsLong());
            assertEquals(7200, event.get("spent_amount").getAsInt());
            assertEquals(3, event.get("event_sequence").getAsLong());
            assertEquals(1, h.calls.size());
        }
    }

    @Test
    public void restartWithoutLocalSlotsLoadsIdentityBeforeSendingUnchangedOrAdvancedCumulativeFills() throws Exception
    {
        for (int filled : new int[]{4, 6})
        {
            try (Harness h = harness())
            {
                h.enableGameTicks();
                for (int slot = 0; slot < 8; slot++) h.liveOffers[slot] = liveOffer(0, 0, 0, 0, GrandExchangeOfferState.EMPTY);
                h.liveOffers[0] = liveOffer(4151, 1200, 10, filled, GrandExchangeOfferState.SELLING);
                ((Map<Integer, MarketPriceView>) get(h.plugin, "marketPrices")).put(4151,
                    new MarketPriceView(4151, 1200, 1100, NOW, NOW, NOW));
                set(h.plugin, "loginReconciliationPending", true);
                set(h.plugin, "serverStateCheckPending", true);
                set(h.plugin, "snapshotPending", true);
                set(h.plugin, "loggedInTicks", 8);
                invoke(h.plugin, "checkServerSlotStateIfPossible");
                assertEquals("Bootstrap starts with one read, no new offer mutation", 1, h.calls.size());
                TestCall state = h.calls.get(0);
                assertEquals("GET", state.request.method());
                assertTrue(state.request.url().encodedPath().endsWith("/ge-slots/state"));
                invoke(h.plugin, "setAccountCash", new Class<?>[]{long.class}, 8000L);
                PendingCashUpdate cash = (PendingCashUpdate) get(h.plugin, "pendingCashUpdate");
                state.respond(loginServerState("server-offer", 4, 1200, "partially_filled"));
                h.drain();
                Object recovered = ((Map<?, ?>) get(h.plugin, "slotSnapshots")).get(1);
                assertEquals("server-offer", get(recovered, "offerId"));
                assertEquals(NOW - 86400, get(recovered, "startedAt"));
                assertEquals(4, get(recovered, "filledQuantity"));
                h.ticks(1);
                List<TestCall> events = h.calls.stream().filter(call -> call.request.url().encodedPath().endsWith("/ge-slots/sync"))
                    .collect(Collectors.toList());
                assertEquals(filled == 4 ? 0 : 1, events.size());
                assertEquals(cash.requestId, ((PendingCashUpdate) get(h.plugin, "pendingCashUpdate")).requestId);
                if (!events.isEmpty())
                {
                    Buffer buffer = new Buffer();
                    events.get(0).request.body().writeTo(buffer);
                    JsonObject event = GSON.fromJson(buffer.readUtf8(), JsonObject.class).getAsJsonObject("event");
                    assertEquals("server-offer", event.get("runelite_offer_id").getAsString());
                    assertEquals(NOW - 86400, event.get("started_at").getAsLong());
                    assertEquals(7200, event.get("spent_amount").getAsInt());
                    assertEquals(3, event.get("event_sequence").getAsInt());
                }
            }
        }
    }

    @Test
    public void loginNeverAdoptsAnUnrelatedRepricedOrTerminalServerOffer() throws Exception
    {
        for (String[] scenario : new String[][]{{"1300", "partially_filled"}, {"1200", "completed"}})
        {
            try (Harness h = harness())
            {
                h.enableGameTicks();
                for (int slot = 0; slot < 8; slot++) h.liveOffers[slot] = liveOffer(0, 0, 0, 0, GrandExchangeOfferState.EMPTY);
                h.liveOffers[0] = liveOffer(4151, 1200, 10, 6, GrandExchangeOfferState.SELLING);
                set(h.plugin, "loginReconciliationPending", true);
                set(h.plugin, "serverStateCheckPending", true);
                set(h.plugin, "loggedInTicks", 8);
                invoke(h.plugin, "checkServerSlotStateIfPossible");
                h.calls.get(0).respond(loginServerState("unrelated-server-offer", 4,
                    Integer.parseInt(scenario[0]), scenario[1]));
                h.drain();
                assertNull(((Map<?, ?>) get(h.plugin, "slotSnapshots")).get(1));
            }
        }
    }

    private static GrandExchangeOffer liveOffer(int item, int price, int total, int filled, GrandExchangeOfferState state)
    {
        return (GrandExchangeOffer) Proxy.newProxyInstance(GrandExchangeOffer.class.getClassLoader(),
            new Class<?>[]{GrandExchangeOffer.class}, (proxy, method, arguments) -> {
                switch (method.getName())
                {
                    case "getState": return state;
                    case "getItemId": return item;
                    case "getPrice": return price;
                    case "getTotalQuantity": return total;
                    case "getQuantitySold": return filled;
                    case "getSpent": return filled * price;
                    default: return primitiveDefault(method.getReturnType());
                }
            });
    }

    @Test
    public void failedLoginStateReadStillJournalsCompletedTradesAndCollectionForRetry() throws Exception
    {
        try (Harness h = harness())
        {
            h.enableGameTicks();
            for (int slot = 0; slot < 8; slot++) h.liveOffers[slot] = liveOffer(0, 0, 0, 0, GrandExchangeOfferState.EMPTY);
            h.liveOffers[0] = liveOffer(4151, 1200, 10, 4, GrandExchangeOfferState.SELLING);
            set(h.plugin, "loginReconciliationPending", true);
            set(h.plugin, "serverStateCheckPending", true);
            set(h.plugin, "loggedInTicks", 8);
            invoke(h.plugin, "checkServerSlotStateIfPossible");
            h.calls.get(0).fail();
            h.drain();
            assertTrue((Long) get(h.plugin, "workerBackoffUntil") > NOW);
            GrandExchangeOfferChanged change = new GrandExchangeOfferChanged();
            change.setSlot(0);
            change.setOffer(liveOffer(4151, 1200, 10, 10, GrandExchangeOfferState.SOLD));
            h.plugin.onGrandExchangeOfferChanged(change);
            change.setOffer(liveOffer(0, 0, 0, 0, GrandExchangeOfferState.EMPTY));
            h.plugin.onGrandExchangeOfferChanged(change);
            EventJournal journal = (EventJournal) get(h.plugin, "eventJournal");
            List<EventJournal.Entry> events = journal.readHead(10);
            assertEquals("The sale and real collection survive the failed bootstrap", 2, events.size());
            JsonObject sold = GSON.fromJson(events.get(0).eventJson, JsonObject.class);
            JsonObject collected = GSON.fromJson(events.get(1).eventJson, JsonObject.class);
            assertEquals("sell_completed", sold.get("eventType").getAsString());
            assertEquals("slot_emptied", collected.get("eventType").getAsString());
            assertEquals(12000, sold.get("spentAmount").getAsInt());
            assertEquals(sold.get("offerId"), collected.get("offerId"));
            assertEquals("Backoff stays bounded despite incoming real events", 1, h.calls.size());
            set(h.plugin, "workerBackoffUntil", 0L);
            invoke(h.plugin, "pumpWorkerRequests");
            TestCall retry = h.calls.get(h.calls.size() - 1);
            assertTrue(retry.request.url().encodedPath().endsWith("/ge-slots/sync"));
            Buffer buffer = new Buffer();
            retry.request.body().writeTo(buffer);
            assertEquals(sold.get("eventId"), GSON.fromJson(buffer.readUtf8(), JsonObject.class)
                .getAsJsonObject("event").get("event_id"));
        }
    }

    private static String loginServerState(String offerId, int filled, int price, String status)
    {
        JsonArray slots = new JsonArray();
        for (int number = 1; number <= 8; number++)
        {
            JsonObject row = new JsonObject();
            row.addProperty("slot_number", number);
            row.addProperty("status", number == 1 ? status : "empty");
            if (number == 1)
            {
                row.addProperty("item_id", 4151);
                row.addProperty("item_name", "Whip");
                row.addProperty("side", "sell");
                row.addProperty("price", price);
                row.addProperty("total_quantity", 10);
                row.addProperty("filled_quantity", filled);
                row.addProperty("spent_amount", filled * price);
                row.addProperty("runelite_offer_id", offerId);
                row.addProperty("started_at", NOW - 86400);
                row.addProperty("last_event_at", NOW - 100);
                row.addProperty("event_sequence", 2);
                row.addProperty("version", 10);
            }
            slots.add(row);
        }
        return "{\"success\":true,\"data\":" + GSON.toJson(slots) + "}";
    }

    @Test
    public void focusedEmptyHourlyResponseAndClosingGeKeepTheFiveGlobalFlips() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW - 60, false, true, 1000));
            h.drain();
            assertEquals(TOP_IDS, h.ids());
            assertTrue(h.view().topOpportunitiesLoaded);

            h.focus(FOCUS);
            TestCall focused = h.requestFocus(FOCUS);
            assertEquals(Integer.toString(FOCUS), focused.request.url().queryParameter("focus_item_id"));
            focused.respond(payload(new ArrayList<>(), FOCUS, NOW, false, true, 2000));
            h.drain();
            assertEquals(TOP_IDS, h.ids());
            assertEquals(FOCUS, h.view().focus.itemId);
            assertEquals(2000, h.view().cash.available);
            assertEquals("The global list keeps its own freshness clock", NOW - 60, h.view().generatedAt);
            assertTrue(h.view().topOpportunitiesLoaded);

            h.closeFocus();
            assertEquals(TOP_IDS, h.ids());
            assertTrue(h.view().topOpportunitiesLoaded);
        }
    }

    @Test
    public void fullResponseUsesCapturedRequestScopeWhenGeFocusChangesDuringHttp() throws Exception
    {
        try (Harness h = harness())
        {
            TestCall full = h.requestFull(true);
            assertNull(full.request.url().queryParameter("focus_item_id"));
            h.focus(FOCUS);
            full.respond(payload(TOP_IDS, 0, NOW, false, true, 1000));
            h.drain();
            assertEquals(TOP_IDS, h.ids());
            assertTrue(h.view().topOpportunitiesLoaded);
        }
    }

    @Test
    public void focusedReplyAfterGeClosesCannotEraseOrRelabelTheGlobalList() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW - 30, false, true, 1000));
            h.drain();
            h.focus(FOCUS);
            TestCall lateFocus = h.requestFocus(FOCUS);
            h.closeFocus();
            lateFocus.respond(payload(new ArrayList<>(), FOCUS, NOW, false, true, 1100));
            h.drain();
            assertEquals(TOP_IDS, h.ids());
            assertEquals(NOW - 30, h.view().generatedAt);
            assertTrue(h.view().topOpportunitiesLoaded);
        }
    }

    @Test
    public void focusedReplyCannotReplaceTheGlobalListEvenWhenHourlyContainsTheFocusItem() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW - 30, false, true, 1000));
            h.drain();
            h.focus(FOCUS);
            h.requestFocus(FOCUS).respond(payload(Arrays.asList(FOCUS), FOCUS, NOW, false, true, 1000));
            h.drain();
            assertEquals(TOP_IDS, h.ids());
            assertEquals(FOCUS, h.view().focus.itemId);
        }
    }

    @Test
    public void periodicFullRefreshStillRunsAtOneHundredTicksWhileFocusRemainsOpen() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW - 30, false, true, 1000));
            h.drain();
            h.focus(FOCUS);
            h.enableGameTicks();
            h.ticks(50);
            assertEquals(1, h.overviewCalls().size());
            h.requestFocus(FOCUS).respond(payload(new ArrayList<>(), FOCUS, NOW, false, true, 1000));
            h.drain();
            h.ticks(49);
            assertEquals("No additional full polling before sixty seconds", 2, h.overviewCalls().size());
            h.ticks(1);
            assertEquals("Focus requests must not postpone the global refresh", 3, h.overviewCalls().size());
            TestCall periodic = h.overviewCalls().get(2);
            assertNull(periodic.request.url().queryParameter("focus_item_id"));
            assertNull(periodic.request.url().queryParameter("fresh_market"));
            assertNull(periodic.request.url().queryParameter("fresh_buy_limits"));
            periodic.respond(payload(TOP_IDS, 0, NOW, false, true, 1000));
            h.drain();
            assertEquals("Refresh advice for the selected item outside the new top", 4, h.overviewCalls().size());
            TestCall refreshedFocus = h.overviewCalls().get(3);
            assertEquals(Integer.toString(FOCUS), refreshedFocus.request.url().queryParameter("focus_item_id"));
            refreshedFocus.respond(payload(new ArrayList<>(), FOCUS, NOW, false, true, 1000));
            h.drain();
            h.ticks(99);
            assertEquals(4, h.overviewCalls().size());
            assertEquals(TOP_IDS, h.ids());
        }
    }

    @Test
    public void availableButStaleEmptyMarketRetainsRowsAndTheirOriginalTimestamp() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW - 60, false, true, 1000));
            h.drain();
            // The existing Worker returns opportunities=true after filtering
            // every old price out. Stale/degraded must also affect usability.
            h.requestFull(true).respond(payload(new ArrayList<>(), 0, NOW - 3600, true, true, 2500));
            h.drain();
            assertEquals(TOP_IDS, h.ids());
            assertEquals(2500, h.view().cash.available);
            assertEquals(NOW - 60, h.view().generatedAt);
            assertFalse(h.view().marketAvailable);
            assertTrue(h.view().marketStale);
            assertTrue(h.view().topOpportunitiesLoaded);
        }
    }

    @Test
    public void unavailableFullMarketRetainsRowsButHealthyEmptyFullMarketClearsThem() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW - 60, false, true, 1000));
            h.drain();
            h.requestFull(true).respond(payload(new ArrayList<>(), 0, NOW, false, false, 2500));
            h.drain();
            assertEquals(TOP_IDS, h.ids());
            assertFalse(h.view().marketAvailable);
            assertEquals(NOW - 60, h.view().generatedAt);
        }
        try (Harness h = harness())
        {
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW - 60, false, true, 1000));
            h.drain();
            h.requestFull(true).respond(payload(new ArrayList<>(), 0, NOW, false, true, 0));
            h.drain();
            assertTrue(h.ids().isEmpty());
            assertTrue(h.view().topOpportunitiesLoaded);
            assertTrue(h.view().marketAvailable);
            assertFalse(h.view().marketStale);
            assertEquals(NOW, h.view().generatedAt);
        }
    }

    @Test
    public void firstFocusedReplyDoesNotClaimTheGlobalListWasLoadedAndSchedulesItsLoad() throws Exception
    {
        try (Harness h = harness())
        {
            h.focus(FOCUS);
            h.requestFocus(FOCUS).respond(payload(new ArrayList<>(), FOCUS, NOW, false, true, 1000));
            h.drain();
            assertFalse(h.view().topOpportunitiesLoaded);
            assertTrue(h.ids().isEmpty());
            assertEquals(2, h.overviewCalls().size());
            TestCall full = h.overviewCalls().get(1);
            assertNull(full.request.url().queryParameter("focus_item_id"));
            full.respond(payload(TOP_IDS, 0, NOW, false, true, 1000));
            h.drain();
            assertEquals(TOP_IDS, h.ids());
            assertTrue(h.view().topOpportunitiesLoaded);
            assertEquals(3, h.overviewCalls().size());
            h.overviewCalls().get(2).respond(payload(new ArrayList<>(), FOCUS, NOW, false, true, 1000));
            h.drain();
            assertEquals(FOCUS, h.view().focus.itemId);
        }
    }

    @Test
    public void currentGlobalRowOverridesThePreviousFocusedQuantityWithoutAnotherFocusRequest() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW - 60, false, true, 1000));
            h.drain();
            h.focus(FOCUS);
            h.requestFocus(FOCUS).respond(payload(new ArrayList<>(), FOCUS, NOW - 30, false, true, 1000));
            h.drain();
            assertEquals(28, h.view().maximumQuantityForItem(FOCUS));
            h.requestFull(true).respond(payload(Arrays.asList(FOCUS, 1001, 1002, 1003, 1004),
                0, NOW, false, true, 1000));
            h.drain();
            assertEquals(1000, h.view().maximumQuantityForItem(FOCUS));
            assertNull("Old focus must not override the newer global quantity or limit", h.view().focus);
            assertEquals("The selected item is already present in this global response", 3, h.overviewCalls().size());
        }
    }

    @Test
    public void globalRefreshDropsObsoleteFocusedQuantityAndRequestsOneBoundedReplacement() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW - 60, false, true, 1000));
            h.drain();
            h.focus(FOCUS);
            h.requestFocus(FOCUS).respond(payload(new ArrayList<>(), FOCUS, NOW - 30, false, true, 1000));
            h.drain();
            assertEquals(28, h.view().maximumQuantityForItem(FOCUS));
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW, false, true, 1000));
            h.drain();
            assertNull("An old executable quantity must not remain active during reload", h.view().opportunityForItem(FOCUS));
            assertEquals(TOP_IDS, h.ids());
            assertEquals(4, h.overviewCalls().size());
            TestCall replacement = h.overviewCalls().get(3);
            assertEquals(Integer.toString(FOCUS), replacement.request.url().queryParameter("focus_item_id"));
            JsonObject updated = GSON.fromJson(payload(new ArrayList<>(), FOCUS, NOW, false, true, 1000), JsonObject.class);
            updated.getAsJsonObject("opportunities").getAsJsonObject("focus").addProperty("maximum_quantity", 12);
            updated.getAsJsonObject("opportunities").getAsJsonObject("focus").addProperty("maximum_cycle_profit", 300);
            replacement.respond(GSON.toJson(updated));
            h.drain();
            assertEquals(12, h.view().maximumQuantityForItem(FOCUS));
            assertEquals(4, h.overviewCalls().size());
        }
    }

    @Test
    public void successfulFocusAdviceCannotHideAnUnavailableGlobalMarket() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW - 60, false, true, 1000));
            h.drain();
            h.requestFull(true).respond(payload(new ArrayList<>(), 0, NOW - 3600, true, true, 1000));
            h.drain();
            SyncHealthTracker health = (SyncHealthTracker) get(h.plugin, "syncHealth");
            assertTrue(health.failed(SyncHealthTracker.Channel.OVERVIEW));
            long retryAt = health.retryAt(SyncHealthTracker.Channel.OVERVIEW);
            h.focus(FOCUS);
            h.requestFocus(FOCUS).respond(payload(new ArrayList<>(), FOCUS, NOW, false, true, 1000));
            h.drain();
            assertEquals(TOP_IDS, h.ids());
            assertFalse(h.view().marketAvailable);
            assertTrue(h.view().marketStale);
            assertTrue(health.failed(SyncHealthTracker.Channel.OVERVIEW));
            assertEquals(retryAt, health.retryAt(SyncHealthTracker.Channel.OVERVIEW));
            assertFalse(health.failed(SyncHealthTracker.Channel.FOCUS));
        }
    }

    @Test
    public void staleEmptyFullMarketRecoversThroughTheScheduledRetryWithoutExtraPolling() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW - 60, false, true, 1000));
            h.drain();
            h.requestFull(true).respond(payload(new ArrayList<>(), 0, NOW - 3600, true, true, 2000));
            h.drain();
            h.enableGameTicks();
            h.ticks(10);
            assertEquals("A backoff must not become a request per game tick", 2, h.overviewCalls().size());
            assertFalse(h.view().marketAvailable);
            h.expireOverviewBackoff();
            h.ticks(1);
            assertEquals(3, h.overviewCalls().size());
            TestCall retry = h.overviewCalls().get(2);
            assertNull(retry.request.url().queryParameter("focus_item_id"));
            assertNull(retry.request.url().queryParameter("fresh_market"));
            h.ticks(20);
            assertEquals("Game ticks during a slow retry must coalesce into the request already in flight",
                3, h.overviewCalls().size());
            assertFalse((boolean) get(h.plugin, "overviewRefreshPending"));
            retry.respond(payload(Arrays.asList(2001, 2002), 0, NOW, false, true, 3000));
            h.drain();
            assertEquals("Successful recovery must not immediately dispatch a duplicate global scan",
                3, h.overviewCalls().size());
            assertFalse((boolean) get(h.plugin, "overviewRefreshPending"));
            assertEquals(Arrays.asList(2001, 2002), h.ids());
            assertTrue(h.view().marketAvailable);
            assertFalse(h.view().marketStale);
            assertFalse(h.health().failed(SyncHealthTracker.Channel.OVERVIEW));
            assertEquals(0, h.health().retryAt(SyncHealthTracker.Channel.OVERVIEW));
            h.ticks(79);
            assertEquals("Recovery resumes the normal one-minute cadence", 3, h.overviewCalls().size());
        }
    }

    @Test
    public void transportFailureMarksRetainedRowsUnavailableAndTheGameTickRetryRestoresThem() throws Exception
    {
        try (Harness h = harness())
        {
            h.attachPanel();
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW - 60, false, true, 1000));
            h.drain();
            h.requestFull(true).fail();
            h.drain();
            assertEquals(TOP_IDS, h.ids());
            assertFalse(h.view().marketAvailable);
            assertEquals(NOW - 60, h.view().generatedAt);
            String unavailableText = h.renderedPanelText();
            assertTrue(unavailableText.contains("Marktgegevens tijdelijk niet beschikbaar"));
            assertTrue(unavailableText.contains("Fixture item 1001"));
            assertFalse(unavailableText.contains("Nog geen uitvoerbare flip"));
            h.expireOverviewBackoff();
            h.enableGameTicks();
            h.ticks(1);
            assertEquals(3, h.overviewCalls().size());
            h.overviewCalls().get(2).respond(payload(TOP_IDS, 0, NOW, false, true, 1000));
            h.drain();
            assertTrue(h.view().marketAvailable);
            assertFalse(h.view().marketStale);
            assertFalse(h.health().failed(SyncHealthTracker.Channel.OVERVIEW));
            assertFalse(h.renderedPanelText().contains("tijdelijk niet beschikbaar"));
        }
    }

    @Test
    public void invalidJsonCannotShowHealthyEmptyMarketAndScheduledRecoveryCanReturnRealEmptyList() throws Exception
    {
        for (String invalid : new String[]{"<html>Worker failed</html>", "{\"success\":true}"})
        {
            try (Harness h = harness())
            {
                h.requestFull(true).respond(payload(TOP_IDS, 0, NOW - 60, false, true, 1000));
                h.drain();
                h.requestFull(true).respond(invalid);
                h.drain();
                assertEquals(TOP_IDS, h.ids());
                assertFalse(h.view().marketAvailable);
                assertTrue(h.health().failed(SyncHealthTracker.Channel.OVERVIEW));
                assertEquals(NOW - 60, h.view().generatedAt);
                h.expireOverviewBackoff();
                h.enableGameTicks();
                h.ticks(1);
                assertEquals(3, h.overviewCalls().size());
                h.overviewCalls().get(2).respond(payload(new ArrayList<>(), 0, NOW, false, true, 1000));
                h.drain();
                assertTrue(h.ids().isEmpty());
                assertTrue(h.view().topOpportunitiesLoaded);
                assertTrue(h.view().marketAvailable);
                assertFalse(h.view().marketStale);
                assertFalse(h.health().failed(SyncHealthTracker.Channel.OVERVIEW));
            }
        }
    }

    @Test
    public void queuedFocusRequestCanRunWhileTheFailedGlobalMarketIsInBackoff() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW - 60, false, true, 1000));
            h.drain();
            TestCall full = h.requestFull(true);
            h.focus(FOCUS);
            invoke(h.plugin, "requestFocusedOverview", new Class<?>[]{int.class}, FOCUS);
            assertEquals("Worker requests stay serialized", 2, h.overviewCalls().size());
            full.respond(payload(new ArrayList<>(), 0, NOW - 3600, true, true, 1000));
            h.drain();
            assertEquals("Global backoff must not starve queued focused advice", 3, h.overviewCalls().size());
            TestCall focus = h.overviewCalls().get(2);
            assertEquals(Integer.toString(FOCUS), focus.request.url().queryParameter("focus_item_id"));
            focus.respond(payload(new ArrayList<>(), FOCUS, NOW, false, true, 1000));
            h.drain();
            assertEquals(TOP_IDS, h.ids());
            assertTrue(h.health().failed(SyncHealthTracker.Channel.OVERVIEW));
            assertFalse(h.health().failed(SyncHealthTracker.Channel.FOCUS));
        }
    }

    @Test
    public void changingTheActualGeSelectionCannotBypassFocusedRequestBackoff() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW, false, true, 1000));
            h.drain();
            h.focus(FOCUS);
            h.requestFocus(FOCUS).fail();
            h.drain();
            assertTrue(h.health().failed(SyncHealthTracker.Channel.FOCUS));
            long retryAt = h.health().retryAt(SyncHealthTracker.Channel.FOCUS);

            h.enableGameTicks();
            // Change the widget only: the real selection handler must detect
            // the new item, retain the circuit breaker, and queue its advice.
            h.focusedItem = FOCUS + 1;
            invoke(h.plugin, "updateFocusedGeItem");
            assertEquals(FOCUS + 1, get(h.plugin, "focusedGeItemId"));
            assertEquals(FOCUS + 1, get(h.plugin, "pendingFocusedOverviewItemId"));
            assertEquals(retryAt, h.health().retryAt(SyncHealthTracker.Channel.FOCUS));
            assertTrue(h.health().failed(SyncHealthTracker.Channel.FOCUS));
            assertEquals("Selecting another item must not start another Worker call during backoff",
                2, h.overviewCalls().size());
            h.ticks(10);
            assertEquals(2, h.overviewCalls().size());
            assertTrue(h.view().marketAvailable);

            h.expireBackoff(SyncHealthTracker.Channel.FOCUS);
            h.ticks(1);
            assertEquals(3, h.overviewCalls().size());
            TestCall retry = h.overviewCalls().get(2);
            assertEquals(Integer.toString(FOCUS + 1), retry.request.url().queryParameter("focus_item_id"));
            retry.respond(payload(new ArrayList<>(), FOCUS + 1, NOW, false, true, 1000));
            h.drain();
            assertEquals(FOCUS + 1, h.view().focus.itemId);
            assertFalse(h.health().failed(SyncHealthTracker.Channel.FOCUS));
            assertEquals(TOP_IDS, h.ids());
        }
    }

    @Test
    public void queuedCashIsSentBeforeGlobalRefreshAfterFocusedHttpCompletes() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW, false, true, 1000));
            h.drain();
            h.focus(FOCUS);
            TestCall focused = h.requestFocus(FOCUS);
            invoke(h.plugin, "requestOverview", new Class<?>[]{boolean.class}, true);
            invoke(h.plugin, "setAccountCash", new Class<?>[]{long.class}, 5000L);
            PendingCashUpdate cashUpdate = (PendingCashUpdate) get(h.plugin, "pendingCashUpdate");
            assertNotNull(cashUpdate);
            assertTrue((boolean) get(h.plugin, "overviewRefreshPending"));
            assertEquals("Cash and the global scan wait for the in-flight focus request", 2, h.calls.size());

            focused.respond(payload(new ArrayList<>(), FOCUS, NOW, false, true, 1000));
            h.drain();
            assertEquals(3, h.calls.size());
            TestCall cash = h.calls.get(2);
            assertEquals("/runelite-api/cash", cash.request.url().encodedPath());
            assertEquals("PUT", cash.request.method());
            Buffer body = new Buffer();
            cash.request.body().writeTo(body);
            JsonObject command = GSON.fromJson(body.readUtf8(), JsonObject.class);
            assertEquals(5000, command.get("cash_balance").getAsLong());
            assertEquals(cashUpdate.requestId, command.get("request_id").getAsString());
            assertEquals("The pending global scan cannot run ahead of a queued cash command",
                2, h.overviewCalls().size());

            cash.respond(payload(TOP_IDS, 0, NOW, false, true, 5000));
            h.drain();
            assertNull(get(h.plugin, "pendingCashUpdate"));
            assertEquals(4, h.calls.size());
            TestCall full = h.calls.get(3);
            assertEquals("/runelite-api/overview", full.request.url().encodedPath());
            assertNull(full.request.url().queryParameter("focus_item_id"));
            full.respond(payload(Arrays.asList(FOCUS, 1001, 1002, 1003, 1004),
                0, NOW, false, true, 5000));
            h.drain();
            assertEquals(5000, h.view().cash.available);
            assertEquals(4, h.calls.size());
        }
    }

    @Test
    public void actualCallbackToPanelRenderingRestoresAllFiveCardsAfterClosingFocusedAdvice() throws Exception
    {
        try (Harness h = harness())
        {
            h.attachPanel();
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW - 60, false, true, 1000));
            h.drain();
            assertFiveCards(h.renderedPanelText());
            h.focus(FOCUS);
            h.requestFocus(FOCUS).respond(payload(new ArrayList<>(), FOCUS, NOW, false, true, 1000));
            h.drain();
            String focused = h.renderedPanelText();
            assertTrue(focused.contains("Geselecteerde flip"));
            assertTrue(focused.contains("Fixture item 1444"));
            h.closeFocus();
            h.drain();
            assertFiveCards(h.renderedPanelText());
        }
    }

    private static void assertFiveCards(String text)
    {
        for (int itemId : TOP_IDS) assertTrue("Missing rendered card " + itemId, text.contains("Fixture item " + itemId));
        assertFalse(text.contains("Nog geen uitvoerbare flip"));
        assertFalse(text.contains("Persoonlijke flips worden opgehaald"));
    }

    @Test
    public void accountSwitchClearsOldRowsAndRejectsThePreviousAccountsQueuedCallback() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(payload(TOP_IDS, 0, NOW - 60, false, true, 1000));
            h.drain();
            TestCall previousAccount = h.requestFull(true);
            previousAccount.respond(payload(Arrays.asList(9999), 0, NOW, false, true, 9999));
            Object credentials = get(h.plugin, "pairingCredentials");
            h.accountHash = 84;
            invoke(h.plugin, "switchToCurrentAccount");
            h.disableUnrelatedScheduling();
            assertSame("An RS account switch keeps this profile's valid device pairing",
                credentials, get(h.plugin, "pairingCredentials"));
            assertTrue((boolean) invoke(h.plugin, "hasDeviceToken"));
            assertEquals(84L, get(h.plugin, "activeAccountHash"));
            assertTrue(h.ids().isEmpty());
            assertFalse(h.view().topOpportunitiesLoaded);
            assertTrue(previousAccount.cancelled);
            invoke(h.plugin, "requestOverview", new Class<?>[]{boolean.class}, true);
            assertTrue((boolean) get(h.plugin, "overviewRefreshPending"));
            assertEquals("Cancellation releases the single Worker slot only when its callback completes",
                2, h.overviewCalls().size());
            h.drain();
            assertEquals("The cancelled callback must dispatch the queued current-account request",
                3, h.overviewCalls().size());
            TestCall currentAccount = h.overviewCalls().get(2);
            assertTrue(h.ids().isEmpty());
            assertFalse(currentAccount.cancelled);
            currentAccount.respond(payload(Arrays.asList(2001), 0, NOW, false, true, 1234));
            h.drain();
            assertEquals(Arrays.asList(2001), h.ids());
            assertEquals(1234, h.view().cash.available);
        }
    }

    @Test
    public void serverAdviceRechecksUsableStaleRowsAndRendersChangedFreshPrices() throws Exception
    {
        try (Harness h = harness())
        {
            h.attachPanel();
            h.requestFull(true).respond(pricedPayload(15, NOW - 3600, NOW - 600, true, 123, 456));
            h.drain();
            assertTrue(h.view().marketAvailable);
            assertTrue(h.view().marketStale);
            assertTrue(h.renderedPanelText().contains("Marktprijzen zijn verouderd"));
            h.enableGameTicks();
            h.ticks(24);
            assertEquals(1, h.overviewCalls().size());
            h.ticks(1);
            assertEquals("Fifteen seconds of game ticks follow the server advice", 2, h.overviewCalls().size());
            TestCall refresh = h.overviewCalls().get(1);
            assertNull(refresh.request.url().queryParameter("fresh_market"));
            assertNull(refresh.request.url().queryParameter("focus_item_id"));
            h.ticks(50);
            assertEquals("A slow public-cache refresh cannot queue duplicate full scans", 2, h.overviewCalls().size());

            // The Worker has now completed its background refresh. Cache reuse
            // is valid: new prices, source snapshot time and trade time are
            // independent evidence, not just a newly generated response clock.
            refresh.respond(pricedPayload(60, NOW, NOW - 120, false, 12345, 12999));
            h.drain();
            assertEquals(12345, h.view().hourly.get(0).buyPrice);
            assertEquals(NOW, h.view().generatedAt);
            assertEquals(NOW - 120, h.view().hourly.get(0).priceUpdatedAt);
            String rendered = h.renderedPanelText();
            assertTrue(rendered.contains("12 345 gp"));
            assertFalse(rendered.contains("Marktprijzen zijn verouderd"));
            assertFalse(h.view().marketStale);
            h.ticks(99);
            assertEquals("The next full interval starts when the slow reply arrives", 2, h.overviewCalls().size());
            h.ticks(1);
            assertEquals(3, h.overviewCalls().size());
            assertNull(h.overviewCalls().get(2).request.url().queryParameter("fresh_market"));
        }
    }

    @Test
    public void focusedResponseCannotReplaceTheServerAdvisedGlobalRefreshClock() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(pricedPayload(15, NOW, NOW - 60, false, 101, 249));
            h.drain();
            h.enableGameTicks();
            h.ticks(10);
            h.focus(FOCUS);
            // This focused response asks for a minute, but it does not own the
            // full-list clock and must not postpone its fifteen-second update.
            h.requestFocus(FOCUS).respond(payload(new ArrayList<>(), FOCUS, NOW, false, true, 1000));
            h.drain();
            h.ticks(14);
            assertEquals(2, h.overviewCalls().size());
            h.ticks(1);
            assertEquals(3, h.overviewCalls().size());
            assertNull(h.overviewCalls().get(2).request.url().queryParameter("focus_item_id"));
            h.ticks(75);
            assertEquals("Repeated due ticks share the current full request", 3, h.overviewCalls().size());
            assertFalse((boolean) get(h.plugin, "overviewRefreshPending"));
        }
    }

    @Test
    public void failedManualFreshIntentSurvivesRetryButDoesNotForceFutureAutomaticRequests() throws Exception
    {
        for (String failure : new String[]{"transport", "http", "json"})
        {
            try (Harness h = harness())
            {
                h.attachPanel();
                h.requestFull(true).respond(pricedPayload(60, NOW - 60, NOW - 120, false, 101, 249));
                h.drain();
                TestCall manual = h.requestFreshMarket();
                assertEquals("1", manual.request.url().queryParameter("fresh_market"));
                if ("transport".equals(failure)) manual.fail();
                else if ("http".equals(failure)) manual.respond(503, "{\"error\":\"temporary failure\"}");
                else manual.respond("{\"success\":true}");
                h.drain();
                assertTrue((boolean) get(h.plugin, "overviewFreshMarketPending"));
                assertTrue(h.renderedPanelText().contains("tijdelijk niet beschikbaar"));
                h.enableGameTicks();
                h.ticks(30);
                assertEquals("An explicit refresh still respects the failure backoff", 2, h.overviewCalls().size());
                h.expireOverviewBackoff();
                h.ticks(1);
                assertEquals(3, h.overviewCalls().size());
                TestCall retry = h.overviewCalls().get(2);
                assertEquals("The manual intent survives " + failure, "1", retry.request.url().queryParameter("fresh_market"));
                retry.respond(pricedPayload(15, NOW, NOW - 10, false, 23456, 24999));
                h.drain();
                assertTrue(h.renderedPanelText().contains("23 456 gp"));
                assertFalse(h.view().marketStale);
                assertFalse((boolean) get(h.plugin, "overviewFreshMarketPending"));
                h.ticks(24);
                assertEquals(3, h.overviewCalls().size());
                h.ticks(1);
                assertEquals(4, h.overviewCalls().size());
                assertNull("Success consumes the force intent", h.overviewCalls().get(3).request.url().queryParameter("fresh_market"));
            }
        }
    }

    @Test
    public void fastServerAdviceDoesNotBypassBackoffOrForceAutomaticRecovery() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(pricedPayload(15, NOW, NOW - 60, false, 101, 249));
            h.drain();
            h.enableGameTicks();
            h.ticks(25);
            assertEquals(2, h.overviewCalls().size());
            h.overviewCalls().get(1).fail();
            h.drain();
            h.ticks(100);
            assertEquals(2, h.overviewCalls().size());
            h.expireOverviewBackoff();
            h.ticks(1);
            assertEquals(3, h.overviewCalls().size());
            assertNull(h.overviewCalls().get(2).request.url().queryParameter("fresh_market"));
        }
    }

    @Test
    public void accountSwitchResetsFastCadenceAndDiscardsLateOldAdvice() throws Exception
    {
        try (Harness h = harness())
        {
            h.requestFull(true).respond(pricedPayload(15, NOW, NOW - 60, false, 101, 249));
            h.drain();
            assertEquals(25, get(h.plugin, "overviewRefreshGameTicks"));
            TestCall old = h.requestFreshMarket();
            old.respond(pricedPayload(15, NOW, NOW, false, 999, 1999));
            h.accountHash = 84;
            invoke(h.plugin, "switchToCurrentAccount");
            h.disableUnrelatedScheduling();
            h.drain();
            assertEquals(100, get(h.plugin, "overviewRefreshGameTicks"));
            assertFalse((boolean) get(h.plugin, "overviewFreshMarketPending"));
            assertTrue(h.view().hourly.isEmpty());
        }
    }

    private static String pricedPayload(int refreshSeconds, long snapshotAt, long transactionAt,
        boolean stale, int buyPrice, int sellPrice)
    {
        JsonObject response = GSON.fromJson(payload(TOP_IDS, 0, snapshotAt, stale, true, 1_000_000), JsonObject.class);
        response.addProperty("refresh_after_seconds", refreshSeconds);
        response.getAsJsonObject("market_refresh").addProperty("upstream_cache_hit", true);
        for (com.google.gson.JsonElement entry : response.getAsJsonObject("opportunities").getAsJsonArray("hourly"))
        {
            JsonObject row = entry.getAsJsonObject();
            row.addProperty("buy_price", buyPrice);
            row.addProperty("sell_price", sellPrice);
            row.addProperty("instant_sell", buyPrice - 1);
            row.addProperty("instant_buy", sellPrice + 1);
            row.addProperty("price_updated_at", transactionAt);
        }
        return GSON.toJson(response);
    }

    private Harness harness() throws Exception
    {
        return new Harness(temporary.newFolder().toPath());
    }

    private static String payload(List<Integer> ids, int focus, long marketAt,
        boolean stale, boolean available, long cash)
    {
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("generated_at", NOW);
        response.addProperty("market_generated_at", marketAt);
        response.addProperty("refresh_after_seconds", 60);
        JsonObject opportunities = new JsonObject();
        JsonArray hourly = new JsonArray();
        for (int id : ids) hourly.add(opportunity(id, marketAt));
        opportunities.add("hourly", hourly);
        opportunities.add("expected", new JsonArray());
        if (focus > 0)
        {
            JsonObject focused = opportunity(focus, marketAt);
            if (!ids.contains(focus))
            {
                // A genuine focus-only Worker payload: thin volume makes this
                // item executable, but its 700 GP cycle cannot enter hourly.
                focused.addProperty("buy_price", 207);
                focused.addProperty("sell_price", 236);
                focused.addProperty("instant_buy", 237);
                focused.addProperty("instant_sell", 206);
                focused.addProperty("maximum_quantity", 28);
                focused.addProperty("maximum_cycle_profit", 700);
                focused.addProperty("maximum_profit_per_hour", 233);
            }
            opportunities.add("focus", focused);
        }
        response.add("opportunities", opportunities);
        JsonObject stats = new JsonObject();
        for (String name : new String[]{"today", "month", "total"})
        {
            stats.add(name, GSON.fromJson("{\"realized_profit\":0,\"roi_percent\":0,\"profit_per_hour\":0," +
                "\"ge_tax\":0,\"trading_volume\":0,\"completed_flips\":0,\"items\":[]}", JsonObject.class));
        }
        response.add("stats", stats);
        JsonObject balance = new JsonObject();
        balance.addProperty("available", cash);
        balance.addProperty("reserved", 0);
        balance.addProperty("available_plus_reserved", cash);
        balance.addProperty("updated_at", NOW);
        response.add("cash", balance);
        response.add("price_tests", new JsonArray());
        JsonObject availability = new JsonObject();
        availability.addProperty("personal_data", true);
        availability.addProperty("market_data", available);
        availability.addProperty("opportunities", available);
        availability.addProperty("degraded", stale || !available);
        availability.addProperty("error_code", available ? "" : "runelite_market_deadline");
        response.add("availability", availability);
        JsonObject market = new JsonObject();
        market.addProperty("stale", stale);
        market.addProperty("available", available);
        market.addProperty("opportunities_available", available);
        market.addProperty("degraded", stale || !available);
        response.add("market_refresh", market);
        return GSON.toJson(response);
    }

    private static JsonObject opportunity(int id, long marketAt)
    {
        JsonObject row = GSON.fromJson("{\"ranking\":\"cycle_profit\",\"buy_price\":101,\"sell_price\":249," +
            "\"instant_buy\":250,\"instant_sell\":100,\"expected_quantity\":0,\"expected_profit\":0," +
            "\"maximum_quantity\":1000,\"maximum_profit_per_hour\":72000,\"maximum_cycle_profit\":144000," +
            "\"official_buy_limit\":1000,\"used_buy_limit\":0,\"remaining_buy_limit\":1000}", JsonObject.class);
        row.addProperty("item_id", id);
        row.addProperty("item_name", "Fixture item " + id);
        row.addProperty("price_updated_at", marketAt);
        return row;
    }

    private static final class Harness implements AutoCloseable
    {
        final OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        final List<TestCall> calls = new ArrayList<>();
        final Deque<Runnable> callbacks = new ArrayDeque<>();
        long accountHash = 42;
        GameState gameState = GameState.LOGIN_SCREEN;
        int focusedItem;
        final GrandExchangeOffer[] liveOffers = new GrandExchangeOffer[8];
        OsrsFlipperSyncPanel panel;

        Harness(Path folder) throws Exception
        {
            Widget setup = widget(false);
            Widget graphic = widget(true);
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                (proxy, method, arguments) ->
                {
                    switch (method.getName())
                    {
                        case "getAccountHash": return accountHash;
                        case "getGameState": return gameState;
                        case "isClientThread": return true;
                        case "getGrandExchangeOffers": return liveOffers;
                        case "getWidget":
                            if (focusedItem <= 0 || arguments.length != 1) return null;
                            if (arguments[0].equals(InterfaceID.GeOffers.SETUP)) return setup;
                            if (arguments[0].equals(InterfaceID.GeOffers.SETUP_GRAPHIC4)) return graphic;
                            return null;
                        default: return primitiveDefault(method.getReturnType());
                    }
                });
            Constructor<?> constructor = ConfigManager.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            ConfigManager manager = (ConfigManager) constructor.newInstance(null, executor, new EventBus(),
                client, GSON, null, null, null);
            Constructor<?> profile = Class.forName("net.runelite.client.config.ConfigProfile")
                .getDeclaredConstructor(long.class);
            profile.setAccessible(true);
            set(manager, "profile", profile.newInstance(0L));
            Constructor<?> data = Class.forName("net.runelite.client.config.ConfigData").getDeclaredConstructor(File.class);
            data.setAccessible(true);
            set(manager, "configProfile", data.newInstance(folder.resolve("fixture.properties").toFile()));
            OsrsFlipperSyncConfig config = new OsrsFlipperSyncConfig()
            {
                @Override public String webappAddress() { return "https://worker.example.test"; }
                @Override public String ownerEmail() { return "owner@example.test"; }
                @Override public String deviceId() { return "fixture-device"; }
            };
            set(plugin, "configManager", manager);
            set(plugin, "config", config);
            set(plugin, "gson", GSON);
            set(plugin, "client", client);
            set(plugin, "eventJournalRoot", folder.resolve("journal"));
            set(plugin, "pairingCredentials", PairingCredentials.create("0", HttpUrl.parse(config.webappAddress()),
                config.ownerEmail(), config.deviceId(), "rlt_" + "fixture".repeat(8)));
            set(plugin, "credentialProfileKey", "0");
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
            disableUnrelatedScheduling();
            set(plugin, "started", true);
        }

        Widget widget(boolean graphic)
        {
            return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
                (proxy, method, arguments) ->
                {
                    if ("isHidden".equals(method.getName())) return focusedItem <= 0;
                    if ("getItemId".equals(method.getName())) return graphic ? focusedItem : -1;
                    if ("getText".equals(method.getName())) return graphic ? "" : "Buy offer";
                    return primitiveDefault(method.getReturnType());
                });
        }

        void disableUnrelatedScheduling() throws Exception
        {
            for (String field : new String[]{"snapshotPending", "serverStateCheckPending", "statusCheckPending",
                "loginReconciliationPending", "geOpenReconciliationPending"}) set(plugin, field, false);
            for (String field : new String[]{"heartbeatTicks", "serverStateTicks", "localReconcileTicks",
                "fullSnapshotTicks", "marketPriceTicks"}) set(plugin, field, -10_000);
        }

        void enableGameTicks() throws Exception
        {
            gameState = GameState.LOGGED_IN;
            disableUnrelatedScheduling();
        }

        void ticks(int count)
        {
            for (int tick = 0; tick < count; tick++) plugin.onGameTick(new GameTick());
        }

        SyncHealthTracker health() throws Exception { return (SyncHealthTracker) get(plugin, "syncHealth"); }

        void expireOverviewBackoff() throws Exception
        {
            expireBackoff(SyncHealthTracker.Channel.OVERVIEW);
        }

        void expireBackoff(SyncHealthTracker.Channel channel) throws Exception
        {
            // Advance only the retry deadline in this fixture; production's
            // real game-tick scheduler still decides and dispatches the retry.
            Map<?, ?> states = (Map<?, ?>) get(health(), "states");
            Object state = states.get(channel);
            assertNotNull("An actual failed response must establish retry state", state);
            assertTrue(health().retryAt(channel) > Instant.now().getEpochSecond());
            set(state, "retryAt", Instant.now().getEpochSecond() - 1);
        }

        void attachPanel() throws Exception
        {
            SwingUtilities.invokeAndWait(() -> panel = new OsrsFlipperSyncPanel(
                null, () -> {}, () -> {}, () -> {}, () -> {}, ignored -> {}));
            set(plugin, "panel", panel);
        }

        String renderedPanelText() throws Exception
        {
            String[] result = new String[1];
            SwingUtilities.invokeAndWait(() ->
            {
                try
                {
                    invoke(panel, "selectTab", new Class<?>[]{String.class}, "opportunities");
                    panel.setSize(240, 1000);
                    layout(panel);
                    BufferedImage image = new BufferedImage(240, 1000, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics();
                    try { panel.paint(graphics); }
                    finally { graphics.dispose(); }
                    result[0] = componentText((Container) get(panel, "opportunitiesList"));
                }
                catch (Exception exception) { throw new AssertionError(exception); }
            });
            return result[0];
        }

        void focus(int itemId) throws Exception
        {
            focusedItem = itemId;
            set(plugin, "focusedGeItemId", itemId);
            set(plugin, "focusedGeSide", "buy");
            set(plugin, "focusedGeContext", FocusedGeItemResolver.EditorContext.NEW_SETUP);
        }

        void closeFocus() throws Exception
        {
            focusedItem = 0;
            invoke(plugin, "updateFocusedGeItem");
        }

        TestCall requestFull(boolean force) throws Exception
        {
            int before = calls.size();
            invoke(plugin, "requestOverview", new Class<?>[]{boolean.class}, force);
            assertEquals("Exactly one Worker request should be dispatched", before + 1, calls.size());
            TestCall call = calls.get(before);
            assertTrue(call.request.url().encodedPath().endsWith("/overview"));
            return call;
        }

        TestCall requestFocus(int itemId) throws Exception
        {
            int before = calls.size();
            invoke(plugin, "requestFocusedOverview", new Class<?>[]{int.class}, itemId);
            assertEquals("Exactly one focused Worker request should be dispatched", before + 1, calls.size());
            return calls.get(before);
        }

        TestCall requestFreshMarket() throws Exception
        {
            int before = calls.size();
            invoke(plugin, "requestFreshMarketOverview");
            assertEquals(before + 1, calls.size());
            return calls.get(before);
        }

        List<TestCall> overviewCalls()
        {
            return calls.stream().filter(call -> call.request.url().encodedPath().endsWith("/overview"))
                .collect(Collectors.toList());
        }

        RuneliteOverviewView view() throws Exception { return (RuneliteOverviewView) get(plugin, "overview"); }
        List<Integer> ids() throws Exception
        {
            return view().hourly.stream().map(row -> row.itemId).collect(Collectors.toList());
        }
        void drain()
        {
            int remaining = 100;
            while (!callbacks.isEmpty())
            {
                assertTrue("Callback scheduling must be bounded", remaining-- > 0);
                callbacks.removeFirst().run();
            }
        }
        @Override public void close() throws Exception
        {
            try { if (panel != null) SwingUtilities.invokeAndWait(panel::dispose); }
            finally { executor.shutdownNow(); }
        }
    }

    private static final class TestCall implements Call
    {
        final Request request;
        Callback callback;
        boolean cancelled;
        TestCall(Request request) { this.request = request; }
        void fail() { callback.onFailure(this, new IOException("Fixture connection timeout")); }
        void respond(String body) throws IOException
        {
            respond(200, body);
        }
        void respond(int status, String body) throws IOException
        {
            assertNotNull(callback);
            callback.onResponse(this, new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("Fixture").body(ResponseBody.create(MediaType.parse("application/json"), body)).build());
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

    private static Object primitiveDefault(Class<?> type)
    {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static void layout(Container container)
    {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container) layout((Container) child);
    }

    private static String componentText(Container container)
    {
        StringBuilder text = new StringBuilder();
        for (Component child : container.getComponents())
        {
            if (child instanceof JLabel) text.append(((JLabel) child).getText()).append('\n');
            if (child instanceof Container) text.append(componentText((Container) child));
        }
        return text.toString();
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
    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception
    {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }
}
