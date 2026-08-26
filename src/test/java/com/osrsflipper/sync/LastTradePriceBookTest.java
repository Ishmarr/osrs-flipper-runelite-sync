package com.osrsflipper.sync;

import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LastTradePriceBookTest
{
    @Test
    public void recordsActualIncrementalBuyAndSellFillPricesImmediately()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.recordTransition(101, "buy", 0, 0, 1, 3_311, 3_500, 10);
        book.recordTransition(101, "sell", 0, 0, 1, 3_230, 3_200, 11);

        LastTradePriceView prices = book.snapshot().get(101);
        assertEquals(3_311, prices.lastBuyPrice);
        assertEquals(3_230, prices.lastSellPrice);
    }

    @Test
    public void calculatesTheLatestPartialFillFromCumulativeOfferAmounts()
    {
        LastTradePriceBook book = new LastTradePriceBook();
        book.recordTransition(202, "buy", 2, 2_000, 5, 5_300, 1_200, 20);

        assertEquals(1_100, book.snapshot().get(202).lastBuyPrice);
    }

    @Test
    public void restoresPricesPerItem()
    {
        LastTradePriceBook.Entry entry = new LastTradePriceBook.Entry();
        entry.itemId = 303;
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
}
