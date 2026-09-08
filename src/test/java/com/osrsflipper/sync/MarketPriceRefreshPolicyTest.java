package com.osrsflipper.sync;

import org.junit.Test;
import static org.junit.Assert.*;

public class MarketPriceRefreshPolicyTest
{
    @Test
    public void retryAfterAcceptsBothSecondsAndHttpDates()
    {
        long now = java.time.Instant.parse("2026-09-08T12:00:00Z").getEpochSecond();
        assertEquals(120, MarketPriceRefreshPolicy.retryAfterSeconds("120", now));
        assertEquals(120, MarketPriceRefreshPolicy.retryAfterSeconds("Tue, 8 Sep 2026 12:02:00 GMT", now));
        assertEquals(0, MarketPriceRefreshPolicy.retryAfterSeconds("Tue, 8 Sep 2026 11:00:00 GMT", now));
        assertEquals(0, MarketPriceRefreshPolicy.retryAfterSeconds("invalid", now));
    }

    @Test
    public void exactItemExpiryCannotMissASecondMinuteAndFocusHasItsOwnDeadline()
    {
        MarketPriceRefreshPolicy policy = new MarketPriceRefreshPolicy();
        MarketPriceView cached = new MarketPriceView(451, 100, 90, 900, 900, 1001);
        assertFalse(policy.due(451, cached, 1060, 60, false));
        assertTrue(policy.due(451, cached, 1061, 60, false));
        assertFalse(policy.due(451, cached, 1005, 5, false));
        assertTrue(policy.due(451, cached, 1006, 5, false));
        assertTrue(policy.due(451, cached, 1002, 60, true));
    }

    @Test
    public void errorsUseBoundedBackoffAndForcedRefreshCannotBypassIt()
    {
        MarketPriceRefreshPolicy policy = new MarketPriceRefreshPolicy();
        long now = 1000;
        for (long delay : new long[]{15, 30, 60, 120, 240, 300, 300})
        {
            policy.failed(451, 503, now, 0);
            assertFalse(policy.due(451, null, now + delay - 1, 5, true));
            assertFalse(policy.sourceReady(now + delay - 1));
            now += delay;
            assertTrue(policy.due(451, null, now, 5, true));
        }
        policy.succeeded(451);
        policy.failed(451, 429, now, 120);
        assertFalse(policy.sourceReady(now + 119));
        assertTrue(policy.sourceReady(now + 120));
        policy.clear();
        assertTrue(policy.due(451, null, now, 5, true));
    }
}
