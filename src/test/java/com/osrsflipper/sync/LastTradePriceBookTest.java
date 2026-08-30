package com.osrsflipper.sync;

import com.google.gson.Gson;
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
    public void crossSlotEventsObservedInTheSameSecondStillFormOnePriceTest()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.recordTransition(111, "buy", 0, 0, 1, 9_141, 1, "completed", 9_143, 100);
        book.recordTransition(111, "sell", 0, 0, 1, 8_000, 1, "completed", 8_000, 100);

        LastTradePriceView prices = book.snapshot().get(111);
        assertEquals(9_141, prices.lastBuyPrice);
        assertEquals(8_000, prices.lastSellPrice);
    }

    @Test
    public void aFullCancelledFillStillPublishesTheOneByOneCycle()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.recordTransition(112, "buy", 0, 0, 1, 9_141, 1, "completed", 9_143, 100);
        book.recordTransition(112, "sell", 0, 0, 1, 8_000, 1, "completed", 8_000, 101);

        assertEquals(9_141, book.snapshot().get(112).lastBuyPrice);
        assertEquals(8_000, book.snapshot().get(112).lastSellPrice);
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
    public void flatAndProfitableOneByOneCyclesPublishTheirExactFillPrices()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.recordTransition(202, "buy", 0, 0, 1, 1_000, 1, "completed", 1_000, 20);
        book.recordTransition(202, "sell", 0, 0, 1, 1_100, 1, "completed", 1_100, 21);
        assertEquals(1_000, book.snapshot().get(202).lastBuyPrice);
        assertEquals(1_100, book.snapshot().get(202).lastSellPrice);

        book.recordTransition(303, "buy", 0, 0, 1, 1_000, 1, "completed", 1_000, 30);
        book.recordTransition(303, "sell", 0, 0, 1, 1_000, 1, "completed", 1_000, 31);

        assertEquals(1_000, book.snapshot().get(303).lastBuyPrice);
        assertEquals(1_000, book.snapshot().get(303).lastSellPrice);
    }

    @Test
    public void aOneByOneCycleOutsideTheAutomaticWindowIsNotAPriceTest()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.recordTransition(404, "buy", 0, 0, 1, 1_000, 1, "completed", 1_000, 30);
        book.recordTransition(404, "sell", 0, 0, 1, 900, 1, "completed", 900, 61);

        assertEquals(0, book.snapshot().get(404).lastBuyPrice);
        assertEquals(0, book.snapshot().get(404).lastSellPrice);
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

    @Test
    public void localPriceTestWinsATimestampTieWithAnAuthoritativeTombstone()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_011, 0, 0, 0, 0, 300)));

        book.recordTransition(1_011, "buy", 0, 0, 1, 2_100, 1, "completed", 2_100, 300);
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_011, 0, 0, 0, 0, 300)));
        book.recordTransition(1_011, "sell", 0, 0, 1, 1_800, 1, "completed", 1_800, 301);

        assertEquals(2_100, book.snapshot().get(1_011).lastBuyPrice);
        assertEquals(1_800, book.snapshot().get(1_011).lastSellPrice);
    }

    @Test
    public void positiveServerGenerationWinsATieWithAnOlderTombstone()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_012, 0, 0, 0, 0, 300)));

        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_012, 2_100, 1_800, 300, 300, 300)));

        assertEquals(2_100, book.snapshot().get(1_012).lastBuyPrice);
        assertEquals(1_800, book.snapshot().get(1_012).lastSellPrice);
        assertEquals(0, book.persistedEntries().get(0).clearedAt);
    }

    @Test
    public void repeatedStaleTombstoneCannotEraseAPositiveSameSecondGeneration()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_014, 0, 0, 0, 0, 300)));
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_014, 2_100, 1_800, 300, 300, 300)));

        Gson gson = new Gson();
        LastTradePriceBook restored = new LastTradePriceBook();
        restored.restore(gson.fromJson(
            gson.toJson(book.persistedEntries()),
            LastTradePriceBook.Entry[].class));
        Map<Integer, Long> repeated = restored.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_014, 0, 0, 0, 0, 300)));

        assertTrue(repeated.isEmpty());
        assertEquals(2_100, restored.snapshot().get(1_014).lastBuyPrice);
        assertEquals(1_800, restored.snapshot().get(1_014).lastSellPrice);

        Map<Integer, Long> nextGeneration = restored.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_014, 0, 0, 0, 0, 301)));
        assertEquals(Long.valueOf(301), nextGeneration.get(1_014));
        assertFalse(restored.snapshot().containsKey(1_014));
    }

    @Test
    public void positiveResponseCannotOverwriteALocalTestChangedAfterTheRequestStarted()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_013, 2_000, 1_900, 300, 300)));
        long requestRevision = book.revision();

        book.recordTransition(
            1_013, "buy", 0, 0, 1, 2_100, 1, "completed", 2_100, 300);
        book.recordTransition(
            1_013, "sell", 0, 0, 1, 1_800, 1, "completed", 1_800, 300);
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_013, 2_000, 1_900, 300, 300)), requestRevision);

        assertEquals(2_100, book.snapshot().get(1_013).lastBuyPrice);
        assertEquals(1_800, book.snapshot().get(1_013).lastSellPrice);
    }

    @Test
    public void delayedPositiveResponseCannotConsumeANewerPendingBuyAtTheSameSecond()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_015, 2_000, 1_900, 300, 300)));
        long requestRevision = book.revision();

        book.recordTransition(
            1_015, "buy", 0, 0, 1, 2_100, 1, "completed", 2_100, 300);
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_015, 2_000, 1_900, 300, 300)), requestRevision);
        book.recordTransition(
            1_015, "sell", 0, 0, 1, 1_800, 1, "completed", 1_800, 301);

        assertEquals(2_100, book.snapshot().get(1_015).lastBuyPrice);
        assertEquals(1_800, book.snapshot().get(1_015).lastSellPrice);
    }

    @Test
    public void delayedTombstoneCannotEraseALocalPositiveGenerationCompletedAfterRequestStart()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_016, 2_000, 1_900, 100, 101)));
        long requestRevision = book.revision();

        book.recordTransition(
            1_016, "buy", 0, 0, 1, 2_100, 1, "completed", 2_100, 300);
        book.recordTransition(
            1_016, "sell", 0, 0, 1, 1_800, 1, "completed", 1_800, 300);
        Map<Integer, Long> clear = book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_016, 0, 0, 0, 0, 300)), requestRevision);

        assertEquals(Long.valueOf(300), clear.get(1_016));
        assertEquals(2_100, book.snapshot().get(1_016).lastBuyPrice);
        assertEquals(1_800, book.snapshot().get(1_016).lastSellPrice);

        assertTrue(book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_016, 0, 0, 0, 0, 300))).isEmpty());
        assertEquals(2_100, book.snapshot().get(1_016).lastBuyPrice);
    }

    @Test
    public void delayedTombstoneCannotConsumeANewerPendingBuyAtTheSameSecond()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_017, 2_000, 1_900, 100, 101)));
        long requestRevision = book.revision();

        book.recordTransition(
            1_017, "buy", 0, 0, 1, 2_100, 1, "completed", 2_100, 300);
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_017, 0, 0, 0, 0, 300)), requestRevision);
        book.recordTransition(
            1_017, "sell", 0, 0, 1, 1_800, 1, "completed", 1_800, 301);

        assertEquals(2_100, book.snapshot().get(1_017).lastBuyPrice);
        assertEquals(1_800, book.snapshot().get(1_017).lastSellPrice);
    }

    @Test
    public void restartKeepsAPendingBuyThatTiesTheAuthoritativeTombstone()
    {
        LastTradePriceBook original = new LastTradePriceBook();
        original.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_018, 0, 0, 0, 0, 300)));
        original.recordTransition(
            1_018, "buy", 0, 0, 1, 2_100, 1, "completed", 2_100, 300);

        LastTradePriceBook restored = new LastTradePriceBook();
        Gson gson = new Gson();
        restored.restore(gson.fromJson(
            gson.toJson(original.persistedEntries()),
            LastTradePriceBook.Entry[].class));
        restored.recordTransition(
            1_018, "sell", 0, 0, 1, 1_800, 1, "completed", 1_800, 301);

        assertEquals(2_100, restored.snapshot().get(1_018).lastBuyPrice);
        assertEquals(1_800, restored.snapshot().get(1_018).lastSellPrice);
    }

    @Test
    public void reportsANewAuthoritativeTombstoneOnlyOnce()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_111, 2_000, 1_900, 100, 101)));

        Map<Integer, Long> first = book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_111, 0, 0, 0, 0, 701)));
        Map<Integer, Long> repeated = book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_111, 0, 0, 0, 0, 701)));

        assertEquals(Long.valueOf(701), first.get(1_111));
        assertTrue(repeated.isEmpty());
        assertFalse(book.snapshot().containsKey(1_111));
    }

    @Test
    public void oldOverviewTombstoneCannotOverrideANewerLocalPriceTest()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_212, 2_000, 1_900, 100, 101)));
        book.recordTransition(
            1_212, "buy", 0, 0, 1, 2_100, 1, "completed", 2_100, 300);
        book.recordTransition(
            1_212, "sell", 0, 0, 1, 1_800, 1, "completed", 1_800, 301);

        Map<Integer, Long> clears = book.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(1_212, 0, 0, 0, 0, 200)));

        assertEquals(Long.valueOf(200), clears.get(1_212));
        assertEquals(2_100, book.snapshot().get(1_212).lastBuyPrice);
        assertEquals(1_800, book.snapshot().get(1_212).lastSellPrice);
    }
}
