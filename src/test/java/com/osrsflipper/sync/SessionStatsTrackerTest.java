package com.osrsflipper.sync;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SessionStatsTrackerTest
{
    @Test
    public void matchesObservedBuysAndSellsAfterTax()
    {
        SessionStatsTracker tracker = new SessionStatsTracker();
        tracker.recordTransition(1, "Test item", "buy", 0, 0, null, 10, 10_000, "completed", 1_000);
        tracker.recordTransition(1, "Test item", "sell", 0, 0, null, 10, 12_000, "completed", 1_200);

        SessionStatsTracker.SessionStatsSnapshot snapshot = tracker.snapshot();
        assertEquals(10_000L, snapshot.invested);
        assertEquals(12_000L, snapshot.grossRevenue);
        assertEquals(240L, snapshot.taxPaid);
        assertEquals(10_000L, snapshot.matchedCost);
        assertEquals(1_760L, snapshot.realizedProfit);
        assertEquals(10, snapshot.matchedQuantity);
        assertEquals(1, snapshot.completedBuyOffers);
        assertEquals(1, snapshot.completedSellOffers);
        assertEquals(17.6d, snapshot.roi(), 0.001d);
    }

    @Test
    public void onlyRecordsTheNewPartOfAPartialFill()
    {
        SessionStatsTracker tracker = new SessionStatsTracker();
        tracker.recordTransition(2, "Partial item", "buy", 0, 0, "active", 4, 400, "partially_filled", 100);
        tracker.recordTransition(2, "Partial item", "buy", 4, 400, "partially_filled", 10, 1_000, "completed", 100);
        tracker.recordTransition(2, "Partial item", "sell", 0, 0, "active", 5, 750, "partially_filled", 150);
        tracker.recordTransition(2, "Partial item", "sell", 5, 750, "partially_filled", 10, 1_500, "completed", 150);

        SessionStatsTracker.SessionStatsSnapshot snapshot = tracker.snapshot();
        assertEquals(10, snapshot.boughtQuantity);
        assertEquals(10, snapshot.soldQuantity);
        assertEquals(1_000L, snapshot.invested);
        assertEquals(1_500L, snapshot.grossRevenue);
        assertEquals(30L, snapshot.taxPaid);
        assertEquals(470L, snapshot.realizedProfit);
    }

    @Test
    public void anUnmatchedSaleDoesNotInventProfit()
    {
        SessionStatsTracker tracker = new SessionStatsTracker();
        tracker.recordTransition(3, "Existing stock", "sell", 0, 0, null, 10, 10_000, "completed", 1_000);

        SessionStatsTracker.SessionStatsSnapshot snapshot = tracker.snapshot();
        assertEquals(0, snapshot.matchedQuantity);
        assertEquals(0L, snapshot.realizedProfit);
        assertEquals(200L, snapshot.taxPaid);
    }

    @Test
    public void geTaxMatchesTheWebappRules()
    {
        assertEquals(0, SessionStatsTracker.calculateTaxPerItem(49, "Cheap item"));
        assertEquals(20, SessionStatsTracker.calculateTaxPerItem(1_000, "Regular item"));
        assertEquals(5_000_000, SessionStatsTracker.calculateTaxPerItem(400_000_000, "Expensive item"));
        assertEquals(0, SessionStatsTracker.calculateTaxPerItem(15_000_000, "Old school bond"));
    }

    @Test
    public void lowestSellPriceBreaksEvenAfterGeTax()
    {
        assertEquals(49, SessionStatsTracker.calculateLowestBreakEvenSellPrice(49, "Cheap item"));
        assertEquals(51, SessionStatsTracker.calculateLowestBreakEvenSellPrice(50, "Regular item"));
        assertEquals(1_020, SessionStatsTracker.calculateLowestBreakEvenSellPrice(1_000, "Regular item"));
        assertEquals(15_000_000,
            SessionStatsTracker.calculateLowestBreakEvenSellPrice(15_000_000, "Old school bond"));
        assertEquals(0, SessionStatsTracker.calculateLowestBreakEvenSellPrice(0, "Regular item"));
    }

    @Test
    public void formatsLongDurationsWithoutWrapping()
    {
        assertEquals("00:00:00", OsrsFlipperSyncPanel.formatDuration(0));
        assertEquals("01:01:01", OsrsFlipperSyncPanel.formatDuration(3_661));
        assertEquals("27:00:00", OsrsFlipperSyncPanel.formatDuration(97_200));
    }
}
