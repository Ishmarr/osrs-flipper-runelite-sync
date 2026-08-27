package com.osrsflipper.sync;

import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
