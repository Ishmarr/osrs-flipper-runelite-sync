package com.osrsflipper.sync;

import java.util.Map;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LastTradePriceBookTest
{
    @Test
    public void publishesBothPricesOnlyAfterACompleteAutomaticPriceTest()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.recordTransition(101, "buy", 0, 0, 1, 3_311, 1, "completed", 3_500, 10);
        assertEquals(0, book.snapshot().get(101).lastBuyPrice);
        assertEquals(0, book.snapshot().get(101).lastSellPrice);

        book.recordTransition(101, "sell", 0, 0, 0, 0, 1, "active", 3_200, 11);
        book.recordTransition(101, "sell", 0, 0, 1, 3_230, 1, "partially_filled", 3_200, 12);

        LastTradePriceView prices = book.snapshot().get(101);
        assertEquals(3_311, prices.lastBuyPrice);
        assertEquals(3_230, prices.lastSellPrice);
    }

    @Test
    public void ordinaryAndPartialFillsNeverChangePublishedPrices()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.recordTransition(202, "buy", 0, 0, 1, 1_200, 1, "completed", 1_200, 10);
        book.recordTransition(202, "sell", 0, 0, 1, 1_100, 1, "completed", 1_100, 11);
        book.recordTransition(202, "buy", 2, 2_000, 5, 5_300, 10, "partially_filled", 1_200, 20);
        book.recordTransition(202, "sell", 0, 0, 5, 5_000, 5, "completed", 1_000, 21);

        assertEquals(1_200, book.snapshot().get(202).lastBuyPrice);
        assertEquals(1_100, book.snapshot().get(202).lastSellPrice);
    }

    @Test
    public void slowOrProfitableOneByOneCyclesAreNotPriceTests()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.recordTransition(202, "buy", 0, 0, 1, 1_000, 1, "completed", 1_000, 20);
        book.recordTransition(202, "sell", 0, 0, 1, 1_100, 1, "completed", 1_100, 21);
        assertEquals(0, book.snapshot().get(202).lastBuyPrice);

        book.recordTransition(202, "buy", 0, 0, 1, 1_000, 1, "completed", 1_000, 30);
        book.recordTransition(202, "sell", 0, 0, 1, 900, 1, "completed", 900, 61);

        assertEquals(0, book.snapshot().get(202).lastBuyPrice);
        assertEquals(0, book.snapshot().get(202).lastSellPrice);
    }

    @Test
    public void restoresPricesPerItem()
    {
        LastTradePriceBook.Entry entry = new LastTradePriceBook.Entry();
        entry.itemId = 303;
        entry.priceTestVersion = 1;
        entry.lastBuyPrice = 900;
        entry.lastSellPrice = 1_000;
        entry.lastBuyAt = 30;
        entry.lastSellAt = 31;

        LastTradePriceBook book = new LastTradePriceBook();
        book.restore(new LastTradePriceBook.Entry[]{entry});
        Map<Integer, LastTradePriceView> restored = book.snapshot();

        assertEquals(900, restored.get(303).lastBuyPrice);
        assertEquals(1_000, restored.get(303).lastSellPrice);
    }

    @Test
    public void discardsLegacyPricesThatCouldHaveComeFromOrdinaryFills()
    {
        LastTradePriceBook.Entry legacy = new LastTradePriceBook.Entry();
        legacy.itemId = 404;
        legacy.lastBuyPrice = 900;
        legacy.lastSellPrice = 1_000;

        LastTradePriceBook book = new LastTradePriceBook();
        book.restore(new LastTradePriceBook.Entry[]{legacy});

        assertEquals(null, book.snapshot().get(404));
    }

    @Test
    public void mergesNewestAccountwideServerPriceTestWithoutOverwritingNewerLocalData()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(505, 2_000, 1_900, 100, 101)));
        assertEquals(2_000, book.snapshot().get(505).lastBuyPrice);

        book.recordTransition(505, "buy", 0, 0, 1, 2_100, 1, "completed", 2_100, 200);
        book.recordTransition(505, "sell", 0, 0, 1, 1_800, 1, "completed", 1_800, 201);
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(505, 2_000, 1_900, 100, 101)));

        assertEquals(2_100, book.snapshot().get(505).lastBuyPrice);
        assertEquals(1_800, book.snapshot().get(505).lastSellPrice);
    }

    @Test
    public void authoritativeTombstoneClearsBothPricesAndBlocksOlderServerData()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(606, 2_000, 1_900, 100, 101)));

        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(606, 0, 0, 0, 0, 200)));
        assertFalse(book.snapshot().containsKey(606));

        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(606, 2_000, 1_900, 100, 101)));
        assertFalse(book.snapshot().containsKey(606));
        assertEquals(200, book.persistedEntries().get(0).clearedAt);
    }

    @Test
    public void laterOneByOnePriceTestReactivatesAResetItem()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(707, 0, 0, 0, 0, 200)));

        book.recordTransition(707, "buy", 0, 0, 1, 2_100, 1, "completed", 2_100, 300);
        book.recordTransition(707, "sell", 0, 0, 1, 1_800, 1, "completed", 1_800, 301);

        assertTrue(book.snapshot().containsKey(707));
        assertEquals(2_100, book.snapshot().get(707).lastBuyPrice);
        assertEquals(1_800, book.snapshot().get(707).lastSellPrice);
    }

    @Test
    public void persistsAndRestoresAnAuthoritativeTombstone()
    {
        LastTradePriceBook original = new LastTradePriceBook();
        original.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(808, 0, 0, 0, 0, 500)));

        LastTradePriceBook restored = new LastTradePriceBook();
        restored.restore(original.persistedEntries().toArray(new LastTradePriceBook.Entry[0]));

        assertFalse(restored.snapshot().containsKey(808));
        assertEquals(500, restored.persistedEntries().get(0).clearedAt);
    }

    @Test
    public void resetClearsOldPublishedPricesButKeepsANewerPendingTest()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(909, 2_000, 1_900, 100, 101)));
        book.recordTransition(909, "buy", 0, 0, 1, 2_100, 1, "completed", 2_100, 300);

        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(909, 0, 0, 0, 0, 200)));
        assertFalse(book.snapshot().containsKey(909));

        book.recordTransition(909, "sell", 0, 0, 1, 1_800, 1, "completed", 1_800, 301);
        assertEquals(2_100, book.snapshot().get(909).lastBuyPrice);
        assertEquals(1_800, book.snapshot().get(909).lastSellPrice);
    }

    @Test
    public void restartKeepsAPendingTestThatIsNewerThanTheTombstone()
    {
        LastTradePriceBook.Entry entry = new LastTradePriceBook.Entry();
        entry.itemId = 1_010;
        entry.priceTestVersion = 1;
        entry.lastBuyPrice = 2_000;
        entry.lastSellPrice = 1_900;
        entry.lastBuyAt = 100;
        entry.lastSellAt = 101;
        entry.clearedAt = 200;
        entry.pendingTestBuyPrice = 2_100;
        entry.pendingTestBuyAt = 300;

        LastTradePriceBook restored = new LastTradePriceBook();
        restored.restore(new LastTradePriceBook.Entry[]{entry});
        restored.recordTransition(
            1_010, "sell", 0, 0, 1, 1_800, 1, "completed", 1_800, 301);

        assertEquals(2_100, restored.snapshot().get(1_010).lastBuyPrice);
        assertEquals(1_800, restored.snapshot().get(1_010).lastSellPrice);
    }
}
