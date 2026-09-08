package com.osrsflipper.sync;

import org.junit.Test;
import static org.junit.Assert.*;

public class MarketPriceViewTest
{
    @Test
    public void eachSideKeepsItsNewestTransactionAndPricesCanMoveDown()
    {
        MarketPriceView initial = new MarketPriceView(451, 250, 100, 100, 110, 120);
        MarketPriceView next = MarketPriceView.accept(initial,
            new MarketPriceView(451, 200, 90, 105, 109, 0), 130);
        assertEquals(200, next.instantBuyPrice);
        assertEquals(100, next.instantSellPrice);
        assertEquals(105, next.instantBuyAt);
        assertEquals(110, next.instantSellAt);
        assertEquals("An overview is not a direct source check", 120, next.fetchedAt);
    }

    @Test
    public void exactSideTimesReplaceAggregateEstimatesWithoutPoisoningFutureObservations()
    {
        MarketPriceView aggregate = new MarketPriceView(451, 200, 100, 200, 200, 0, false, false);
        MarketPriceView exact = MarketPriceView.accept(aggregate,
            new MarketPriceView(451, 250, 100, 190, 200, 0), 210);
        assertEquals(250, exact.instantBuyPrice);
        assertEquals(190, exact.instantBuyAt);
        MarketPriceView laterAggregate = MarketPriceView.accept(exact,
            new MarketPriceView(451, 200, 100, 205, 205, 0, false, false), 210);
        assertEquals(250, laterAggregate.instantBuyPrice);
        assertEquals(190, laterAggregate.instantBuyAt);
        assertEquals(0, laterAggregate.fetchedAt);
    }

    @Test
    public void invalidOrFutureSidesCannotEraseTheLastQuoteOrClaimASuccessfulCheck()
    {
        MarketPriceView initial = new MarketPriceView(451, 250, 100, 100, 110, 120);
        assertNull(MarketPriceView.accept(initial, new MarketPriceView(451, 0, 500, 125, 1000, 130), 130));
        MarketPriceView partial = MarketPriceView.accept(initial,
            new MarketPriceView(451, 0, 110, 125, 125, 130), 130);
        assertEquals(250, partial.instantBuyPrice);
        assertEquals(110, partial.instantSellPrice);
        assertEquals(130, partial.fetchedAt);
    }
}
