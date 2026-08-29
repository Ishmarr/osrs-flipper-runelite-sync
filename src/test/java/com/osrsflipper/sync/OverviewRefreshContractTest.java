package com.osrsflipper.sync;

import okhttp3.HttpUrl;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
            false);

        assertEquals("100", url.queryParameter("day_start"));
        assertEquals("200", url.queryParameter("month_start"));
        assertEquals("12780", url.queryParameter("focus_item_id"));
        assertNull(url.queryParameter("fresh_market"));
    }

    @Test
    public void explicitRefreshRequestsFreshMarketData()
    {
        HttpUrl url = OsrsFlipperSyncPlugin.overviewUrl(
            HttpUrl.parse("https://example.test/runelite-api/overview"),
            100,
            200,
            0,
            true);

        assertEquals("1", url.queryParameter("fresh_market"));
        assertNull(url.queryParameter("focus_item_id"));
    }
}
