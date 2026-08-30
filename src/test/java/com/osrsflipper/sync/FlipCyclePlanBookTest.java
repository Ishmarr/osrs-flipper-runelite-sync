package com.osrsflipper.sync;

import com.google.gson.Gson;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FlipCyclePlanBookTest
{
    @Test
    public void completedBuySurvivesTheEmptyGapWithItsFrozenPlan()
    {
        FlipCyclePlanBook book = new FlipCyclePlanBook();
        observeBuy(book, "buy-snapdragon", "buy-snapdragon", 5_803, 100);

        FlipCyclePlanBook.Cycle selected = book.selectForSetup(3004);

        assertNotNull(selected);
        assertEquals(7_631, selected.frozenBuyPrice);
        assertEquals(7_888, selected.sellTargetPrice);
        assertEquals(7_786, selected.lowestSellPrice);
        assertEquals(5_803, selected.availableQuantity());
    }

    @Test
    public void zeroFillActiveBuyKeepsItsPlanButCancellationRemovesIt()
    {
        FlipCyclePlanBook book = new FlipCyclePlanBook();
        book.recordBuy(
            "buy-bolts", "buy-bolts", 1, 29455, "Moonlight antler bolts",
            229, 244, 234, 10_999, 0, "active", 100, 100);

        FlipCyclePlanBook.Cycle active = book.selectOpenBuy(29455);
        assertNotNull(active);
        assertEquals(10_999, active.displayedBuyQuantity());
        assertEquals(229, active.frozenBuyPrice);

        book.recordBuy(
            "buy-bolts", "buy-bolts", 1, 29455, "Moonlight antler bolts",
            229, 244, 234, 10_999, 0, "cancelled", 100, 110);

        assertNull(book.selectOpenBuy(29455));
        assertEquals(0, book.size());
    }

    @Test
    public void anAmbiguousLegacyEmptyBuyCannotResurrectSoldInventory()
    {
        FlipCyclePlanBook book = new FlipCyclePlanBook();
        book.recordBuy(
            "old-buy", "old-buy", 1, 3004, "Snapdragon potion (unf)",
            7_631, 7_888, 7_786, 5_803, 5_803, "empty", 100, 200);

        assertEquals(0, book.size());
        assertNull(book.selectForSetup(3004));
    }

    @Test
    public void partialCancelledSellLeavesOnlyTheUnsoldQuantityAvailable()
    {
        FlipCyclePlanBook book = new FlipCyclePlanBook();
        observeBuy(book, "buy-snapdragon", "buy-snapdragon", 5_803, 100);
        book.recordSell(
            "buy-snapdragon", "sell-first", 4, 3004, "Snapdragon potion (unf)",
            7_631, 7_888, 7_786, 5_803, 1_000, "partially_filled", 200, 210);

        assertEquals(0, book.cycle("buy-snapdragon").availableQuantity());

        book.recordSell(
            "buy-snapdragon", "sell-first", 4, 3004, "Snapdragon potion (unf)",
            7_631, 7_888, 7_786, 5_803, 1_000, "cancelled", 200, 220);
        FlipCyclePlanBook.Cycle remaining = book.selectForSell(3004, 4_803, 300);

        assertNotNull(remaining);
        assertEquals(1_000, remaining.soldQuantity());
        assertEquals(4_803, remaining.availableQuantity());
        assertEquals("buy-snapdragon", remaining.cycleId);
    }

    @Test
    public void duplicateEventsAndMultipleSellsNeverDoubleCountFills()
    {
        FlipCyclePlanBook book = new FlipCyclePlanBook();
        observeBuy(book, "buy-1", "buy-1", 100, 100);
        recordCompletedSell(book, "buy-1", "sell-40", 40, 200);
        recordCompletedSell(book, "buy-1", "sell-40", 40, 200);

        FlipCyclePlanBook.Cycle afterFirst = book.selectForSell(3004, 60, 300);
        assertNotNull(afterFirst);
        assertEquals(40, afterFirst.soldQuantity());
        assertEquals(60, afterFirst.availableQuantity());

        recordCompletedSell(book, "buy-1", "sell-60", 60, 300);
        FlipCyclePlanBook.Cycle closed = book.cycle("buy-1");
        assertEquals(100, closed.soldQuantity());
        assertTrue(closed.isClosed());
        assertNull(book.selectForSetup(3004));
    }

    @Test
    public void parallelSellReservationsCannotOverAllocateOneBuy()
    {
        FlipCyclePlanBook book = new FlipCyclePlanBook();
        observeBuy(book, "buy-1", "buy-1", 100, 100);
        book.recordSell(
            "buy-1", "sell-60", 2, 3004, "Snapdragon potion (unf)",
            7_631, 7_888, 7_786, 60, 0, "active", 200, 200);

        assertEquals(40, book.cycle("buy-1").availableQuantity());
        assertNull(book.selectForSell(3004, 41, 300));
        assertNotNull(book.selectForSell(3004, 40, 300));
    }

    @Test
    public void missedCancelledEventCanReleaseAndReplaceAPartialSell()
    {
        FlipCyclePlanBook book = new FlipCyclePlanBook();
        observeBuy(book, "buy-1", "buy-1", 100, 100);
        book.recordSell(
            "buy-1", "sell-original", 2, 3004, "Snapdragon potion (unf)",
            7_631, 7_888, 7_786, 100, 40, "partially_filled", 200, 210);

        book.releaseSell("buy-1", "sell-original", 40, 220);
        assertEquals(60, book.cycle("buy-1").availableQuantity());

        book.recordSell(
            "buy-1", "sell-repriced", 2, 3004, "Snapdragon potion (unf)",
            7_631, 7_900, 7_786, 60, 0, "active", 220, 220);
        assertEquals(0, book.cycle("buy-1").availableQuantity());
        book.recordSell(
            "buy-1", "sell-repriced", 2, 3004, "Snapdragon potion (unf)",
            7_631, 7_900, 7_786, 60, 60, "completed", 220, 230);

        assertTrue(book.cycle("buy-1").isClosed());
    }

    @Test
    public void directBuyModifyKeepsTheFirstPriceAndAddsLaterFills()
    {
        FlipCyclePlanBook book = new FlipCyclePlanBook();
        observeBuy(book, "buy-root", "buy-original", 25, 100);
        book.recordBuy(
            "buy-root", "buy-modified", 1, 3004, "Snapdragon potion (unf)",
            7_700, 7_900, 7_850, 75, 75, "completed", 100, 200);

        FlipCyclePlanBook.Cycle cycle = book.cycle("buy-root");
        assertEquals(7_631, cycle.frozenBuyPrice);
        assertEquals(7_786, cycle.lowestSellPrice);
        assertEquals(100, cycle.acquiredQuantity());
    }

    @Test
    public void persistedStateRestoresFrozenPricesAndRemainingQuantity()
    {
        FlipCyclePlanBook original = new FlipCyclePlanBook();
        observeBuy(original, "buy-1", "buy-1", 100, 100);
        recordCompletedSell(original, "buy-1", "sell-25", 25, 200);

        Gson gson = new Gson();
        String json = gson.toJson(original.persistedCycles());
        FlipCyclePlanBook restored = new FlipCyclePlanBook();
        restored.restore(gson.fromJson(json, FlipCyclePlanBook.Cycle[].class));
        FlipCyclePlanBook.Cycle cycle = restored.selectForSetup(3004);

        assertNotNull(cycle);
        assertEquals(7_631, cycle.frozenBuyPrice);
        assertEquals(7_786, cycle.lowestSellPrice);
        assertEquals(75, cycle.availableQuantity());
        assertFalse(cycle.isClosed());
    }

    @Test
    public void delayedSellGuidanceCanFillOrRaiseOnlyTheOpenCycleTarget()
    {
        FlipCyclePlanBook book = new FlipCyclePlanBook();
        book.recordBuy(
            "buy-1", "buy-1", 1, 3004, "Snapdragon potion (unf)",
            7_631, 0, 7_786, 5_803, 5_803, "completed", 100, 100);

        assertTrue(book.raiseSellTarget(3004, 7_888));
        assertFalse(book.raiseSellTarget(3004, 7_800));
        assertEquals(7_888, book.selectForSetup(3004).sellTargetPrice);
    }

    @Test
    public void restoredEmptyGapRequestsAMissingSellTarget()
    {
        FlipCyclePlanBook original = new FlipCyclePlanBook();
        original.recordBuy(
            "buy-1", "buy-1", 1, 3004, "Snapdragon potion (unf)",
            7_631, 0, 7_786, 5_803, 5_803, "completed", 100, 100);
        original.recordBuy(
            "buy-1", "buy-1", 1, 3004, "Snapdragon potion (unf)",
            7_631, 0, 7_786, 5_803, 5_803, "empty", 100, 200);

        Gson gson = new Gson();
        FlipCyclePlanBook restored = new FlipCyclePlanBook();
        restored.restore(gson.fromJson(
            gson.toJson(original.persistedCycles()),
            FlipCyclePlanBook.Cycle[].class));

        assertTrue(restored.openItemIds().contains(3004));
        assertTrue(restored.needsSellTarget(3004));
        assertTrue(restored.raiseSellTarget(3004, 7_888));
        assertFalse(restored.needsSellTarget(3004));
    }

    @Test
    public void guidanceRefreshCannotReorderParallelCyclesForTheSameItem()
    {
        FlipCyclePlanBook book = new FlipCyclePlanBook();
        book.recordBuy(
            "older-buy", "older-buy", 1, 3004, "Snapdragon potion (unf)",
            7_631, 0, 7_786, 10, 10, "completed", 100, 100);
        book.recordBuy(
            "newer-buy", "newer-buy", 2, 3004, "Snapdragon potion (unf)",
            7_700, 8_000, 7_857, 10, 10, "completed", 200, 200);

        assertTrue(book.raiseSellTarget(3004, 7_888));

        FlipCyclePlanBook.Cycle selected = book.selectForSell(3004, 10, 300);
        assertNotNull(selected);
        assertEquals("newer-buy", selected.cycleId);
        assertEquals(7_700, selected.frozenBuyPrice);
        assertEquals(7_857, selected.lowestSellPrice);
    }

    @Test
    public void guidanceOnlySnapshotReplayCannotReorderCyclesAfterRestart()
    {
        FlipCyclePlanBook book = new FlipCyclePlanBook();
        book.recordBuy(
            "older-buy", "older-buy", 1, 3004, "Snapdragon potion (unf)",
            7_631, 7_888, 7_786, 10, 10, "completed", 100, 100);
        book.recordBuy(
            "newer-buy", "newer-buy", 2, 3004, "Snapdragon potion (unf)",
            7_700, 8_000, 7_857, 10, 10, "completed", 200, 200);

        book.recordBuy(
            "older-buy", "older-buy", 1, 3004, "Snapdragon potion (unf)",
            7_631, 7_950, 7_786, 10, 10, "completed", 100, 300);

        FlipCyclePlanBook.Cycle selected = book.selectForSell(3004, 10, 400);
        assertNotNull(selected);
        assertEquals("newer-buy", selected.cycleId);
        assertEquals(200, selected.lastEventAt);
    }

    @Test
    public void aSellCannotBeLinkedToInventoryAcquiredAfterThatSellStarted()
    {
        FlipCyclePlanBook book = new FlipCyclePlanBook();
        book.recordBuy(
            "buy-1", "buy-1", 1, 3004, "Snapdragon potion (unf)",
            7_631, 7_888, 7_786, 10, 0, "active", 100, 100);
        book.recordBuy(
            "buy-1", "buy-1", 1, 3004, "Snapdragon potion (unf)",
            7_631, 7_888, 7_786, 10, 10, "completed", 100, 200);

        assertNull(book.selectForSell(3004, 10, 150));
        assertNotNull(book.selectForSell(3004, 10, 250));
        assertEquals(200, book.cycle("buy-1").lastAcquiredAt);
    }

    @Test
    public void authoritativeTombstoneRemovesOnlyCyclesOlderThanItsCutoff()
    {
        FlipCyclePlanBook book = new FlipCyclePlanBook();
        observeBuy(book, "older-buy", "older-buy", 10, 100);
        observeBuyGeneration(book, "newer-buy", "newer-buy", 10, 500);
        book.recordBuy(
            "other-item", "other-item", 3, 4151, "Abyssal whip",
            1_000, 1_100, 1_020, 10, 10, "completed", 600, 600);

        assertEquals(1, book.expireOpenCycles(3004, 500));
        assertNull(book.cycle("older-buy"));
        assertNotNull(book.cycle("newer-buy"));
        assertNotNull(book.cycle("other-item"));
    }

    @Test
    public void delayedFirstTombstoneCannotDeleteANewerWikiCycle()
    {
        LastTradePriceBook prices = new LastTradePriceBook();
        prices.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(3004, 7_889, 7_630, 100, 101)));
        FlipCyclePlanBook cycles = new FlipCyclePlanBook();
        observeBuy(cycles, "expired-buy", "expired-buy", 10, 100);
        observeBuyGeneration(cycles, "new-wiki-buy", "new-wiki-buy", 10, 600);

        Map<Integer, Long> delayedClear = prices.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(3004, 0, 0, 0, 0, 500)));
        for (Map.Entry<Integer, Long> cleared : delayedClear.entrySet())
        {
            cycles.expireOpenCycles(cleared.getKey(), cleared.getValue());
        }

        assertEquals(Long.valueOf(500), delayedClear.get(3004));
        assertNull(cycles.cycle("expired-buy"));
        assertNotNull(cycles.cycle("new-wiki-buy"));
    }

    @Test
    public void repeatedOldTombstoneCannotDeleteANewerWikiCycle()
    {
        LastTradePriceBook prices = new LastTradePriceBook();
        prices.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(3004, 7_889, 7_630, 100, 101)));
        FlipCyclePlanBook cycles = new FlipCyclePlanBook();
        observeBuy(cycles, "expired-buy", "expired-buy", 10, 100);

        Map<Integer, Long> firstClear = prices.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(3004, 0, 0, 0, 0, 500)));
        for (Map.Entry<Integer, Long> cleared : firstClear.entrySet())
        {
            cycles.expireOpenCycles(cleared.getKey(), cleared.getValue());
        }
        assertNull(cycles.cycle("expired-buy"));

        observeBuyGeneration(cycles, "new-wiki-buy", "new-wiki-buy", 10, 600);
        Map<Integer, Long> repeatedClear = prices.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(3004, 0, 0, 0, 0, 500)));
        for (Map.Entry<Integer, Long> cleared : repeatedClear.entrySet())
        {
            cycles.expireOpenCycles(cleared.getKey(), cleared.getValue());
        }

        assertTrue(repeatedClear.isEmpty());
        assertNotNull(cycles.cycle("new-wiki-buy"));
    }

    @Test
    public void authoritativeExpiryLeavesAlreadyClosedHistoryAlone()
    {
        FlipCyclePlanBook book = new FlipCyclePlanBook();
        observeBuy(book, "closed-buy", "closed-buy", 10, 100);
        recordCompletedSell(book, "closed-buy", "closed-sell", 10, 200);

        assertEquals(0, book.expireOpenCycles(3004, 500));
        assertNotNull(book.cycle("closed-buy"));
        assertTrue(book.cycle("closed-buy").isClosed());
    }

    private static void observeBuy(
        FlipCyclePlanBook book,
        String cycleId,
        String offerId,
        int filledQuantity,
        long eventAt)
    {
        book.recordBuy(
            cycleId,
            offerId,
            1,
            3004,
            "Snapdragon potion (unf)",
            7_631,
            7_888,
            7_786,
            Math.max(1, filledQuantity),
            filledQuantity,
            "completed",
            100,
            eventAt);
    }

    private static void observeBuyGeneration(
        FlipCyclePlanBook book,
        String cycleId,
        String offerId,
        int filledQuantity,
        long eventAt)
    {
        book.recordBuy(
            cycleId,
            offerId,
            1,
            3004,
            "Snapdragon potion (unf)",
            7_631,
            7_888,
            7_786,
            Math.max(1, filledQuantity),
            filledQuantity,
            "completed",
            eventAt,
            eventAt);
    }

    private static void recordCompletedSell(
        FlipCyclePlanBook book,
        String cycleId,
        String offerId,
        int quantity,
        long eventAt)
    {
        book.recordSell(
            cycleId,
            offerId,
            2,
            3004,
            "Snapdragon potion (unf)",
            7_631,
            7_888,
            7_786,
            quantity,
            quantity,
            "completed",
            eventAt,
            eventAt);
    }
}
