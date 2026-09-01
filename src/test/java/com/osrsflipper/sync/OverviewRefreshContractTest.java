package com.osrsflipper.sync;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import okhttp3.HttpUrl;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OverviewRefreshContractTest
{
    @Test
    public void automaticOverviewRefreshRunsAboutOncePerMinute()
    {
        assertEquals(100, OsrsFlipperSyncPlugin.OVERVIEW_GAME_TICKS);
    }

    @Test
    public void periodicOverviewKeepsTheWorkerMarketCache()
    {
        HttpUrl url = OsrsFlipperSyncPlugin.overviewUrl(
            HttpUrl.parse("https://example.test/runelite-api/overview"),
            100,
            200,
            12_780,
            false,
            new HashSet<>(Arrays.asList(4151, 2434)));

        assertEquals("100", url.queryParameter("day_start"));
        assertEquals("200", url.queryParameter("month_start"));
        assertEquals("12780", url.queryParameter("focus_item_id"));
        assertEquals("2434,4151", url.queryParameter("tracked_item_ids"));
        assertNull(url.queryParameter("fresh_market"));
        assertNull(url.queryParameter("fresh_buy_limits"));
    }

    @Test
    public void confirmedSlotSyncRequestsAuthoritativeBuyLimitUsage()
    {
        HttpUrl url = OsrsFlipperSyncPlugin.overviewUrl(
            HttpUrl.parse("https://example.test/runelite-api/overview"),
            100,
            200,
            0,
            false,
            true,
            null);

        assertEquals("1", url.queryParameter("fresh_buy_limits"));
        assertNull(url.queryParameter("fresh_market"));
    }

    @Test
    public void outboxBatchRefreshesBuyLimitsOnlyForAppliedOrDuplicateBuyEvents()
    {
        assertTrue(OsrsFlipperSyncPlugin.syncResultChangesBuyLimit("buy", "applied"));
        assertTrue(OsrsFlipperSyncPlugin.syncResultChangesBuyLimit("buy", "duplicate"));
        assertFalse(OsrsFlipperSyncPlugin.syncResultChangesBuyLimit("buy", "rejected"));
        assertFalse(OsrsFlipperSyncPlugin.syncResultChangesBuyLimit("sell", "applied"));

        assertEquals(OsrsFlipperSyncPlugin.OutboxOverviewRefresh.NONE,
            OsrsFlipperSyncPlugin.outboxOverviewRefresh(false, true));
        assertEquals(OsrsFlipperSyncPlugin.OutboxOverviewRefresh.FRESH_BUY_LIMITS,
            OsrsFlipperSyncPlugin.outboxOverviewRefresh(true, true));
        assertEquals(OsrsFlipperSyncPlugin.OutboxOverviewRefresh.NORMAL,
            OsrsFlipperSyncPlugin.outboxOverviewRefresh(true, false));
    }

    @Test
    public void explicitRefreshRequestsFreshMarketData()
    {
        HttpUrl url = OsrsFlipperSyncPlugin.overviewUrl(
            HttpUrl.parse("https://example.test/runelite-api/overview"),
            100,
            200,
            0,
            true,
            null);

        assertEquals("1", url.queryParameter("fresh_market"));
        assertNull(url.queryParameter("focus_item_id"));
        assertNull(url.queryParameter("tracked_item_ids"));
    }

    @Test
    public void overviewResponseMustBelongToTheCurrentAccountRequestAndConnectionContext()
    {
        assertTrue(OsrsFlipperSyncPlugin.isCurrentOverviewRequest(
            42, 7, 3, 42, 7, 3, true));
        assertFalse(OsrsFlipperSyncPlugin.isCurrentOverviewRequest(
            42, 7, 3, 84, 7, 3, true));
        assertFalse(OsrsFlipperSyncPlugin.isCurrentOverviewRequest(
            42, 7, 3, 42, 8, 3, true));
        assertFalse(OsrsFlipperSyncPlugin.isCurrentOverviewRequest(
            42, 7, 3, 42, 7, 4, true));
        assertFalse(OsrsFlipperSyncPlugin.isCurrentOverviewRequest(
            42, 7, 3, 42, 7, 3, false));
    }

    @Test
    public void pairingWaitsForAnExistingOverviewRequest() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        Field overviewInFlight = OsrsFlipperSyncPlugin.class.getDeclaredField("overviewInFlight");
        overviewInFlight.setAccessible(true);
        overviewInFlight.setBoolean(plugin, true);
        Method anyWorkerRequestInFlight = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "anyWorkerRequestInFlight");
        anyWorkerRequestInFlight.setAccessible(true);

        assertTrue((Boolean) anyWorkerRequestInFlight.invoke(plugin));
    }

    @Test
    public void invalidatingAnAccountOrConnectionContextClearsBuyLimitRefreshState()
        throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        Field freshPending = OsrsFlipperSyncPlugin.class.getDeclaredField(
            "overviewFreshBuyLimitsPending");
        Field batchDirty = OsrsFlipperSyncPlugin.class.getDeclaredField(
            "outboxBatchBuyLimitDirty");
        freshPending.setAccessible(true);
        batchDirty.setAccessible(true);
        freshPending.setBoolean(plugin, true);
        batchDirty.setBoolean(plugin, true);

        Method invalidate = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "invalidateOverviewContext");
        invalidate.setAccessible(true);
        invalidate.invoke(plugin);

        assertFalse(freshPending.getBoolean(plugin));
        assertFalse(batchDirty.getBoolean(plugin));
    }
}
