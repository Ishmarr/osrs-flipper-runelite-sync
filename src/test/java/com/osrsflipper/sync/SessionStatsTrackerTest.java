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
    public void geTaxUsesTwoPercentRoundedDownAndThePerItemCap()
    {
        assertEquals(0, SessionStatsTracker.calculateTaxPerItem(49, 4151));
        assertEquals(1, SessionStatsTracker.calculateTaxPerItem(50, 4151));
        assertEquals(20, SessionStatsTracker.calculateTaxPerItem(1_049, 4151));
        assertEquals(4_999_999, SessionStatsTracker.calculateTaxPerItem(249_999_999, 4151));
        assertEquals(5_000_000, SessionStatsTracker.calculateTaxPerItem(250_000_000, 4151));
        assertEquals(5_000_000, SessionStatsTracker.calculateTaxPerItem(Integer.MAX_VALUE, 4151));
        assertEquals(0, SessionStatsTracker.calculateTaxPerItem(15_000_000, 13190));
    }

    @Test
    public void profitPerItemUsesTheDisplayedPricesAndGeTax()
    {
        assertEquals(176L,
            SessionStatsTracker.calculateProfitPerItem(1_000, 1_200, 4151));
        assertEquals(-20L,
            SessionStatsTracker.calculateProfitPerItem(1_000, 1_000, 4151));
        assertEquals(200L,
            SessionStatsTracker.calculateProfitPerItem(1_000, 1_200, 13190));
        assertEquals(0L,
            SessionStatsTracker.calculateProfitPerItem(0, 1_200, 4151));
    }

    @Test
    public void lowestSellPriceBreaksEvenAfterGeTax()
    {
        assertEquals(49, SessionStatsTracker.calculateLowestBreakEvenSellPrice(49, 4151));
        assertEquals(51, SessionStatsTracker.calculateLowestBreakEvenSellPrice(50, 4151));
        assertEquals(1_020, SessionStatsTracker.calculateLowestBreakEvenSellPrice(1_000, 4151));
        assertEquals(15_000_000,
            SessionStatsTracker.calculateLowestBreakEvenSellPrice(15_000_000, 13190));
        assertEquals(0, SessionStatsTracker.calculateLowestBreakEvenSellPrice(0, 4151));
    }

    @Test
    public void hammerHasNoTaxOrArtificialBreakEvenPremiumEvenWithoutItsName()
    {
        SessionStatsTracker tracker = new SessionStatsTracker();
        tracker.recordTransition(2347, null, "buy", 0, 0, null, 10, 10_000, "completed", 1_000);
        tracker.recordTransition(2347, "Item 2347", "sell", 0, 0, null, 10, 12_000, "completed", 1_200);
        assertEquals(0, tracker.snapshot().taxPaid);
        assertEquals(2_000, tracker.snapshot().realizedProfit);
        assertEquals(1_000, SessionStatsTracker.calculateLowestBreakEvenSellPrice(1_000, 2347));
        assertEquals(Integer.MAX_VALUE,
            SessionStatsTracker.calculateLowestBreakEvenSellPrice(Integer.MAX_VALUE, 2347));

        SessionStatsTracker incorrectlyNamed = new SessionStatsTracker();
        incorrectlyNamed.recordTransition(4151, "Old school bond", "sell", 0, 0, null,
            1, 1_000, "completed", 1_000);
        assertEquals(20, incorrectlyNamed.snapshot().taxPaid);
    }

    @Test
    public void utilityExemptionsIncludeAllPotionDosesAndDoNotExtendToSimilarItems()
    {
        for (int itemId : new int[] {233, 952, 1733, 1735, 1755, 1785, 2347,
            5325, 5329, 5331, 5341, 5343, 8794, 13190, 3008, 3010, 3012, 3014,
            558, 806, 807, 808, 882, 884, 886, 315, 329, 347, 351, 355, 361,
            365, 379, 1891, 2140, 2142, 2309, 2327, 2552, 3853, 8007, 8008,
            8009, 8010, 8011, 8013, 28790, 28824})
        {
            assertEquals("Exempt item " + itemId, 0,
                SessionStatsTracker.calculateTaxPerItem(1_000, itemId));
        }
        // Shark, trout, super energy, rune arrow and Watchtower teleport are
        // not exempt merely because another food, potion, ammo or tablet is.
        for (int itemId : new int[] {385, 333, 3016, 892, 8012, 0, -1})
        {
            assertEquals("Taxable item " + itemId, 20,
                SessionStatsTracker.calculateTaxPerItem(1_000, itemId));
        }
    }

    @Test
    public void formatsLongDurationsWithoutWrapping()
    {
        assertEquals("00:00:00", OsrsFlipperSyncPanel.formatDuration(0));
        assertEquals("01:01:01", OsrsFlipperSyncPanel.formatDuration(3_661));
        assertEquals("27:00:00", OsrsFlipperSyncPanel.formatDuration(97_200));
    }
}
