package com.osrsflipper.sync;

import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Deque;
import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import okhttp3.HttpUrl;
import org.junit.Test;
import static org.junit.Assert.*;

public class OverviewFailureRegressionTest
{
    @Test
    public void wikiFallbackKeepsPricesButNeverClaimsQuantityOrTotalProfitAreZero()
    {
        SelectedGeOpportunityResolver.Resolution resolved = SelectedGeOpportunityResolver.resolve(
            FocusedGeItemResolver.EditorContext.NEW_SETUP, 385, "Anglerfish", "buy", null,
            new MarketPriceView(385, 1878, 1810, 100, 100, 100), null, null);
        assertFalse(resolved.opportunity.hasQuantity());
        assertEquals("Niet beschikbaar", OsrsFlipperSyncPanel.quantityText(resolved.opportunity));
        assertEquals("Alleen prijsadvies", OsrsFlipperSyncPanel.cycleProfitText(resolved.opportunity, 0));
        assertEquals(1811, resolved.price("buy"));
        assertEquals(1877, resolved.price("sell"));
    }

    @Test
    public void exhaustedLimitAndAuthoritativeZeroRemainRealZero()
    {
        RuneliteOverviewView.Opportunity fullLimit = new RuneliteOverviewView.Opportunity(
            385, "Anglerfish", "cycle_profit", 1811, 1877, 1878, 1810,
            100, 0, 100, 0, 0, 100, 10000, 10000, 0);
        assertTrue(fullLimit.hasQuantity());
        assertEquals("0", OsrsFlipperSyncPanel.quantityText(fullLimit));
        assertEquals("0 GP", OsrsFlipperSyncPanel.cycleProfitText(fullLimit, 0));
        RuneliteOverviewView.Opportunity zero = new RuneliteOverviewView.Opportunity(
            385, "Anglerfish", "cycle_profit", 1811, 1877, 1878, 1810, 0, 0, 0, 0, 0, 100);
        assertTrue(zero.hasQuantity());
        assertEquals("0", OsrsFlipperSyncPanel.quantityText(zero));
    }

    @Test
    public void missingQuantityInOtherwiseValidRowIsUnknownNotZero() throws Exception
    {
        Class<?> type = nested("OpportunityData");
        Object dto = new Gson().fromJson("{\"item_id\":385,\"buy_price\":1811}", type);
        Method toView = type.getDeclaredMethod("toView");
        toView.setAccessible(true);
        assertFalse(((RuneliteOverviewView.Opportunity) toView.invoke(dto)).hasQuantity());
    }

    @Test
    public void failedAndPartialResponsesPreserveLastGoodOverview() throws Exception
    {
        String[] payloads = {"<html>Worker exceeded resource limits</html>", "{\"success\":true}",
            completePayload().replace("\"cash\":{\"available\":1000", "\"cash\":{\"available\":null"),
            completePayload().replace("\"realized_profit\":0", "\"realized_profit\":null"),
            completePayload().replace("\"hourly\":[]", "\"hourly\":[null]"),
            completePayload().replace("\"hourly\":[]", "\"hourly\":[{}]"),
            completePayload().replace("\"hourly\":[]", "\"hourly\":[],\"focus\":{\"item_id\":-1}")};
        for (String payload : payloads)
        {
            OsrsFlipperSyncPlugin plugin = plugin();
            RuneliteOverviewView previous = lastGoodOverview();
            set(plugin, "overview", previous);
            handle(plugin, payload.startsWith("<") ? 503 : 200, payload);
            RuneliteOverviewView current = (RuneliteOverviewView) get(plugin, "overview");
            assertPreservedOverviewData(previous, current);
            assertFalse(current.marketAvailable);
            assertTrue(current.marketStale);
            assertTrue(previous.marketAvailable);
            assertFalse(previous.marketStale);
            assertTrue(health(plugin).failed(SyncHealthTracker.Channel.OVERVIEW));
            assertFalse((Boolean) get(plugin, "overviewInFlight"));
        }
    }

    @Test
    public void focusedResponseForAnotherItemCannotReplaceValidGlobalOrSelectedData() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = plugin();
        RuneliteOverviewView previous = lastGoodOverview();
        set(plugin, "overview", previous);
        set(plugin, "focusedGeItemId", 385);
        Method method = OsrsFlipperSyncPlugin.class.getDeclaredMethod("handleOverviewResponse",
            int.class, String.class, long.class, long.class, long.class, long.class, int.class);
        method.setAccessible(true);
        method.invoke(plugin, 200,
            completePayload().replace("\"hourly\":[]", "\"hourly\":[],\"focus\":{\"item_id\":386}"),
            get(plugin, "activeAccountHash"), 0L, 0L, 0L, 385);

        RuneliteOverviewView current = (RuneliteOverviewView) get(plugin, "overview");
        assertPreservedOverviewData(previous, current);
        assertTrue(current.marketAvailable);
        assertFalse(current.marketStale);
        assertTrue(health(plugin).failed(SyncHealthTracker.Channel.FOCUS));
        assertFalse(health(plugin).failed(SyncHealthTracker.Channel.OVERVIEW));
        assertFalse((Boolean) get(plugin, "overviewInFlight"));
    }

    @Test
    public void completeZeroStatsAreValidButMissingNumbersAreNot() throws Exception
    {
        Class<?> type = nested("OverviewResponse");
        Method complete = type.getDeclaredMethod("isComplete");
        complete.setAccessible(true);
        assertTrue((Boolean) complete.invoke(new Gson().fromJson(completePayload(), type)));
        assertFalse((Boolean) complete.invoke(new Gson().fromJson(
            completePayload().replace("\"roi_percent\":0,", ""), type)));
    }

    @Test
    public void degradedMarketResponseUpdatesPersonalDataButKeepsLastGoodOpportunities() throws Exception
    {
        RuneliteOverviewView.Opportunity retained = new RuneliteOverviewView.Opportunity(
            385, "Anglerfish", "cycle_profit", 1811, 1877, 1878, 1810,
            100, 2900, 100, 2900, 2900, 100);
        RuneliteOverviewView previous = new RuneliteOverviewView(
            java.util.Collections.emptyList(),
            java.util.Collections.singletonList(retained),
            retained,
            new RuneliteOverviewView.PeriodStats(123, 1, 2, 3, 4, 5),
            RuneliteOverviewView.PeriodStats.empty(),
            RuneliteOverviewView.PeriodStats.empty(),
            java.util.Collections.emptyList(),
            new RuneliteOverviewView.CashBalance(777, 0, 777, 1),
            1);
        String degraded = completePayload().replace(
            "\"price_tests\":[]",
            "\"price_tests\":[],\"availability\":{" +
                "\"personal_data\":true,\"market_data\":false," +
                "\"opportunities\":false,\"degraded\":true," +
                "\"error_code\":\"market_deadline_exceeded\"}");
        Class<?> type = nested("OverviewResponse");
        Object response = new Gson().fromJson(degraded, type);
        Method available = type.getDeclaredMethod("opportunitiesAvailable");
        available.setAccessible(true);
        assertFalse((Boolean) available.invoke(response));
        Method toView = type.getDeclaredMethod("toView", RuneliteOverviewView.class);
        toView.setAccessible(true);
        RuneliteOverviewView current = (RuneliteOverviewView) toView.invoke(response, previous);
        assertNotSame(previous, current);
        assertSame(retained, current.opportunityForItem(385));
        assertEquals(0, current.today.realizedProfit);
        assertEquals(1000, current.cash.available);
    }

    @Test
    public void accountTransitionDiscardsPendingAndInFlightCashValues() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = plugin();
        set(plugin, "pendingCashUpdate", PendingCashUpdate.create(1234L));
        set(plugin, "cashInFlightUpdate", PendingCashUpdate.create(1200L));
        set(plugin, "cashInFlight", true);
        Method clear = OsrsFlipperSyncPlugin.class.getDeclaredMethod("clearAccountScopedCashQueue");
        clear.setAccessible(true);
        clear.invoke(plugin);
        assertNull(get(plugin, "pendingCashUpdate"));
        assertNull(get(plugin, "cashInFlightUpdate"));
        assertFalse((Boolean) get(plugin, "cashInFlight"));
    }

    @Test
    public void manualFocusRefreshCannotBypassBackoffOrStartAnotherRequest() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = plugin();
        set(plugin, "started", true);
        set(plugin, "overviewInFlight", false);
        set(plugin, "config", new OsrsFlipperSyncConfig()
        {
            @Override public String ownerEmail() { return "owner@example.test"; }
            @Override public String deviceId() { return "fixture-device"; }
        });
        set(plugin, "pairingCredentials", PairingCredentials.create("default",
            HttpUrl.parse(new OsrsFlipperSyncConfig() {}.webappAddress()),
            "owner@example.test", "fixture-device", "rlt_" + "test".repeat(12)));
        health(plugin).fail(SyncHealthTracker.Channel.OVERVIEW, "time-out", System.currentTimeMillis() / 1000);
        long retry = health(plugin).retryAt(SyncHealthTracker.Channel.OVERVIEW);
        Method request = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "requestOverview", boolean.class, boolean.class, boolean.class);
        request.setAccessible(true);
        for (int attempt = 0; attempt < 10; attempt++)
        {
            request.invoke(plugin, true, true, true);
        }
        assertFalse((Boolean) get(plugin, "overviewInFlight"));
        assertTrue((Boolean) get(plugin, "overviewRefreshPending"));
        assertTrue((Boolean) get(plugin, "overviewFreshBuyLimitsPending"));
        assertEquals(retry, health(plugin).retryAt(SyncHealthTracker.Channel.OVERVIEW));
    }

    @Test
    public void safeTraceIdRejectsArbitraryHeaderText()
    {
        assertEquals("rl-1234_ab:56", OsrsFlipperSyncPlugin.safeTraceId("rl-1234_ab:56"));
        assertEquals("niet beschikbaar", OsrsFlipperSyncPlugin.safeTraceId("Bearer secret\nnext"));
        assertEquals("niet beschikbaar", OsrsFlipperSyncPlugin.safeTraceId(null));
    }

    @Test
    public void incompleteSuccessfulEventResponseStaysQueuedForIdempotentRetry() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = plugin();
        Object queued = queueEvent(plugin, "health-test-event");
        handleEvent(plugin, "health-test-event", "{\"success\":true}");
        assertEquals(1, ((Deque<?>) get(plugin, "outbox")).size());
        assertTrue(health(plugin).failed(SyncHealthTracker.Channel.EVENTS));
        assertTrue((Long) get(queued, "nextAttemptAt") > System.currentTimeMillis() / 1000);
    }

    @Test
    public void explicitSemanticRejectionUnblocksFifoAndWaitsForMatchedState() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = plugin();
        queueEvent(plugin, "health-test-event");
        handleEvent(plugin, "health-test-event", eventResponse("health-test-event", "rejected"));
        assertTrue(((Deque<?>) get(plugin, "outbox")).isEmpty());
        assertTrue((Boolean) get(plugin, "serverStateCheckPending"));
        assertTrue(health(plugin).failed(SyncHealthTracker.Channel.EVENTS));

        queueEvent(plugin, "next-event");
        handleEvent(plugin, "next-event", eventResponse("next-event", "applied"));
        assertTrue(((Deque<?>) get(plugin, "outbox")).isEmpty());
        assertTrue(health(plugin).failed(SyncHealthTracker.Channel.EVENTS));

        StringBuilder state = new StringBuilder("{\"success\":true,\"data\":[");
        for (int slot = 1; slot <= 8; slot++)
        {
            if (slot > 1) { state.append(','); }
            state.append("{\"slot_number\":").append(slot).append(",\"status\":\"empty\"}");
        }
        state.append("]}");
        Method handleState = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "handleServerStateResponse", int.class, String.class);
        handleState.setAccessible(true);
        handleState.invoke(plugin, 200, state.toString());
        assertFalse(health(plugin).failed(SyncHealthTracker.Channel.EVENTS));
        assertFalse((Boolean) get(plugin, "serverStateCheckPending"));
    }

    @Test
    public void acceptedAndDuplicateAcksRemoveEventAndRecoverDeliveryHealth() throws Exception
    {
        for (String outcome : new String[]{"applied", "duplicate"})
        {
            OsrsFlipperSyncPlugin plugin = plugin();
            queueEvent(plugin, "health-test-event");
            health(plugin).fail(SyncHealthTracker.Channel.EVENTS, "netwerkfout", 100);
            handleEvent(plugin, "health-test-event", eventResponse("health-test-event", outcome));
            assertTrue(((Deque<?>) get(plugin, "outbox")).isEmpty());
            assertFalse(health(plugin).failed(SyncHealthTracker.Channel.EVENTS));
        }
    }

    @Test
    public void missingOrContradictoryAckDoesNotSilentlyDiscardEvent() throws Exception
    {
        String valid = eventResponse("health-test-event", "applied");
        for (String response : new String[]{valid.replace("\"success\":true,", ""),
            valid.replace("\"success\":true", "\"success\":false"),
            valid.replace("health-test-event", "other-event")})
        {
            OsrsFlipperSyncPlugin plugin = plugin();
            queueEvent(plugin, "health-test-event");
            handleEvent(plugin, "health-test-event", response);
            assertEquals(1, ((Deque<?>) get(plugin, "outbox")).size());
            assertTrue(health(plugin).failed(SyncHealthTracker.Channel.EVENTS));
        }
    }

    private static String eventResponse(String eventId, String outcome)
    {
        boolean rejected = "rejected".equals(outcome);
        return "{\"success\":" + !rejected + ",\"summary\":{\"received\":1,\"rejected\":" +
            (rejected ? 1 : 0) + "},\"results\":[{\"event_id\":\"" + eventId +
            "\",\"outcome\":\"" + outcome + "\"}]}";
    }

    private static Object queueEvent(OsrsFlipperSyncPlugin plugin, String eventId) throws Exception
    {
        Class<?> eventType = nested("SyncEvent");
        java.lang.reflect.Constructor<?> eventConstructor = eventType.getDeclaredConstructor();
        eventConstructor.setAccessible(true);
        Object event = eventConstructor.newInstance();
        set(event, "eventId", eventId);
        Class<?> queueType = nested("QueuedEvent");
        java.lang.reflect.Constructor<?> queueConstructor = queueType.getDeclaredConstructor();
        queueConstructor.setAccessible(true);
        Object queued = queueConstructor.newInstance();
        set(queued, "event", event);
        @SuppressWarnings("unchecked") Deque<Object> queue = (Deque<Object>) get(plugin, "outbox");
        queue.add(queued);
        return queued;
    }

    private static void handleEvent(OsrsFlipperSyncPlugin plugin, String eventId, String response) throws Exception
    {
        Method handle = OsrsFlipperSyncPlugin.class.getDeclaredMethod("handleHttpResponse",
            String.class, int.class, String.class);
        handle.setAccessible(true);
        handle.invoke(plugin, eventId, 200, response);
    }

    @Test
    public void allTabBannerFitsNarrowPanelWithTwoFailuresAndUnknownQuantity() throws Exception
    {
        OsrsFlipperSyncPanel[] holder = new OsrsFlipperSyncPanel[1];
        SyncHealthTracker health = new SyncHealthTracker();
        health.fail(SyncHealthTracker.Channel.OVERVIEW, "HTTP 503", 100);
        health.fail(SyncHealthTracker.Channel.EVENTS, "netwerkfout/time-out", 100);
        SwingUtilities.invokeAndWait(() ->
        {
            holder[0] = new OsrsFlipperSyncPanel(null, () -> {}, () -> {}, () -> {}, () -> {}, ignored -> {});
            holder[0].updateHealth(health.banner(7));
            holder[0].updateFocusedItem(385, "buy", SelectedGeOpportunityResolver.resolve(
                FocusedGeItemResolver.EditorContext.NEW_SETUP, 385, "Anglerfish", "buy", null,
                new MarketPriceView(385, 1878, 1810, 100, 100, 100), null, null).opportunity);
        });
        SwingUtilities.invokeAndWait(() ->
        {
            OsrsFlipperSyncPanel panel = holder[0];
            try
            {
                Method tab = OsrsFlipperSyncPanel.class.getDeclaredMethod("selectTab", String.class);
                tab.setAccessible(true);
                for (String name : new String[]{"slots", "stats", "sync", "opportunities"})
                {
                    tab.invoke(panel, name);
                    assertTrue(((JLabel) get(panel, "healthBanner")).isVisible());
                }
                panel.setSize(240, 760);
                layout(panel);
                JLabel banner = (JLabel) get(panel, "healthBanner");
                assertTrue(banner.getPreferredSize().width <= panel.getWidth());
                assertTrue(banner.getHeight() < 260);
                BufferedImage screenshot = new BufferedImage(240, 760, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = screenshot.createGraphics();
                panel.paint(graphics);
                graphics.dispose();
                Path output = Paths.get("build/reports/health-panel.png");
                Files.createDirectories(output.getParent());
                ImageIO.write(screenshot, "png", output.toFile());
            }
            catch (Exception exception)
            {
                throw new AssertionError(exception);
            }
            finally
            {
                panel.dispose();
            }
        });
    }

    private static void layout(Container parent)
    {
        parent.doLayout();
        for (Component child : parent.getComponents())
        {
            if (child instanceof Container)
            {
                layout((Container) child);
            }
        }
    }

    private static String completePayload()
    {
        String period = "{\"realized_profit\":0,\"roi_percent\":0,\"profit_per_hour\":0," +
            "\"ge_tax\":0,\"trading_volume\":0,\"completed_flips\":0,\"items\":[]}";
        return "{\"success\":true,\"generated_at\":1000,\"opportunities\":{\"hourly\":[]}," +
            "\"stats\":{\"today\":" + period + ",\"month\":" + period + ",\"total\":" + period + "}," +
            "\"cash\":{\"available\":1000,\"reserved\":0,\"available_plus_reserved\":1000,\"updated_at\":1}," +
            "\"price_tests\":[]}";
    }

    private static RuneliteOverviewView lastGoodOverview()
    {
        RuneliteOverviewView.Opportunity first = new RuneliteOverviewView.Opportunity(
            385, "Anglerfish", "cycle_profit", 1811, 1877, 1878, 1810,
            100, 2900, 100, 2900, 2900, 100);
        RuneliteOverviewView.Opportunity second = new RuneliteOverviewView.Opportunity(
            4151, "Abyssal whip", "cycle_profit", 1000, 1100, 1101, 999,
            100, 7000, 100, 7000, 7000, 100);
        return new RuneliteOverviewView(java.util.Collections.emptyList(),
            java.util.Arrays.asList(first, second), first,
            new RuneliteOverviewView.PeriodStats(123, 1, 2, 3, 4, 5),
            RuneliteOverviewView.PeriodStats.empty(), RuneliteOverviewView.PeriodStats.empty(),
            java.util.Collections.emptyList(), new RuneliteOverviewView.CashBalance(777, 0, 777, 1), 100);
    }

    private static void assertPreservedOverviewData(RuneliteOverviewView previous, RuneliteOverviewView current)
    {
        assertEquals(previous.hourly, current.hourly);
        assertEquals(2, current.hourly.size());
        assertSame(previous.hourly.get(0), current.hourly.get(0));
        assertSame(previous.hourly.get(1), current.hourly.get(1));
        assertSame(previous.focus, current.focus);
        assertEquals(previous.generatedAt, current.generatedAt);
        assertTrue(current.topOpportunitiesLoaded);
        assertSame(previous.cash, current.cash);
        assertSame(previous.today, current.today);
    }

    private static OsrsFlipperSyncPlugin plugin() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        set(plugin, "gson", new Gson());
        set(plugin, "config", new OsrsFlipperSyncConfig() {});
        set(plugin, "overviewInFlight", true);
        return plugin;
    }

    private static void handle(OsrsFlipperSyncPlugin plugin, int status, String body) throws Exception
    {
        Method method = OsrsFlipperSyncPlugin.class.getDeclaredMethod("handleOverviewResponse",
            int.class, String.class, long.class, long.class, long.class, long.class);
        method.setAccessible(true);
        method.invoke(plugin, status, body, get(plugin, "activeAccountHash"), 0L, 0L, 0L);
    }

    private static SyncHealthTracker health(OsrsFlipperSyncPlugin plugin) throws Exception
    {
        return (SyncHealthTracker) get(plugin, "syncHealth");
    }

    private static Class<?> nested(String name) throws Exception
    {
        return Class.forName(OsrsFlipperSyncPlugin.class.getName() + "$" + name);
    }

    private static Object get(Object object, String name) throws Exception
    {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws Exception
    {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
