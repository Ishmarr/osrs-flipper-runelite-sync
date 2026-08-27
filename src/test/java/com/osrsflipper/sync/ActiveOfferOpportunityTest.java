package com.osrsflipper.sync;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ActiveOfferOpportunityTest
{
    @Test
    public void focusedExistingOfferFallsBackToMatchingLocalSlot()
    {
        FlipperOfferView buy = offer(1, "buy", 641, 756, 640);
        FlipperOfferView sell = offer(2, "sell", 700, 757, 639);

        RuneliteOverviewView.Opportunity opportunity =
            OsrsFlipperSyncPanel.activeOfferOpportunity(
                21163, "sell", Arrays.asList(buy, sell));

        assertEquals("active_sell", opportunity.ranking);
        assertEquals(700, opportunity.sellPrice);
        assertEquals(757, opportunity.instantBuy);
        assertEquals(639, opportunity.instantSell);
    }

    @Test
    public void missingSlotDoesNotInventAnOpportunity()
    {
        assertNull(OsrsFlipperSyncPanel.activeOfferOpportunity(
            999, "buy", Arrays.asList(offer(1, "buy", 100, 110, 90))));
    }

    @Test
    public void activeBuyKeepsItsFrozenAdviceAheadOfTheLiveOverview()
    {
        FlipperOfferView buy = offer(1, "buy", 292_362, 302_073, 292_361);
        RuneliteOverviewView.Opportunity live = new RuneliteOverviewView.Opportunity(
            21163, "Emerald amulet", "focus",
            296_550, 300_168, 300_169, 296_549,
            50, 1_000, 50, 1_000, 100_000, 2);
        RuneliteOverviewView overview = new RuneliteOverviewView(
            java.util.Collections.emptyList(),
            java.util.Collections.emptyList(),
            live,
            null,
            null,
            null,
            2);

        RuneliteOverviewView.Opportunity focused = OsrsFlipperSyncPanel.focusedOpportunity(
            21163,
            "buy",
            overview,
            java.util.Collections.singletonList(buy));

        assertEquals(292_362, focused.buyPrice);
        assertEquals(700, focused.sellPrice);
        assertEquals(302_073, focused.instantBuy);
        assertEquals(292_361, focused.instantSell);

        LastTradePriceView laterPriceTest = new LastTradePriceView(
            21163,
            310_000,
            305_000,
            3,
            3);
        assertEquals(292_362,
            OsrsFlipperSyncPanel.displayedBuyPrice(focused, laterPriceTest));
        assertEquals(700,
            OsrsFlipperSyncPanel.displayedSellPrice(focused, laterPriceTest));
    }

    private static FlipperOfferView offer(
        int slot,
        String side,
        int price,
        int instantBuy,
        int instantSell)
    {
        return new FlipperOfferView(
            slot,
            21163,
            "Emerald amulet",
            side,
            price,
            3_805,
            0,
            "active",
            1,
            0,
            price,
            700,
            instantBuy,
            instantSell);
    }
}
