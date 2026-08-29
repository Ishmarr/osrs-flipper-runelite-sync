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
    public void activeBuyKeepsFrozenGuidanceButPanelPrioritizesPersonalFillPrices()
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
        assertEquals(310_000,
            OsrsFlipperSyncPanel.displayedBuyPrice(focused, laterPriceTest));
        assertEquals(305_000,
            OsrsFlipperSyncPanel.displayedSellPrice(focused, laterPriceTest));
    }

    @Test
    public void activeBuyShowsChangingWikiPricesWithoutMovingFrozenGuidance()
    {
        RuneliteOverviewView.Opportunity first =
            OsrsFlipperSyncPanel.activeOfferOpportunity(
                21163,
                "buy",
                java.util.Collections.singletonList(
                    offer(1, "buy", 292_362, 302_073, 292_361)));
        RuneliteOverviewView.Opportunity refreshed =
            OsrsFlipperSyncPanel.activeOfferOpportunity(
                21163,
                "buy",
                java.util.Collections.singletonList(
                    offer(1, "buy", 292_362, 300_169, 296_549)));

        assertEquals(292_362, first.buyPrice);
        assertEquals(first.buyPrice, refreshed.buyPrice);
        assertEquals(first.sellPrice, refreshed.sellPrice);
        assertEquals(300_169, refreshed.instantBuy);
        assertEquals(296_549, refreshed.instantSell);
    }

    @Test
    public void activeSellKeepsTheCarriedBuyAndSellPlanWhileWikiMoves()
    {
        FlipperOfferView sell = new FlipperOfferView(
            2,
            21163,
            "Emerald amulet",
            "sell",
            900,
            50,
            0,
            "active",
            10,
            0,
            700,
            1_100,
            1_300,
            600);

        RuneliteOverviewView.Opportunity active =
            OsrsFlipperSyncPanel.activeOfferOpportunity(
                21163,
                "sell",
                java.util.Collections.singletonList(sell));

        assertEquals(700, active.buyPrice);
        assertEquals(1_100, active.sellPrice);
        assertEquals(1_300, active.instantBuy);
        assertEquals(600, active.instantSell);
    }

    @Test
    public void activeBuyAndLinkedSellKeepTheSameFrozenLowestPriceWhileWikiMoves()
    {
        FlipperOfferView buy = new FlipperOfferView(
            1, 5952, "Antidote++(4)", "buy", 9_398,
            2_846, 0, "active", 10, 0,
            9_398, 9_681, 9_682, 9_397, 9_589);
        FlipperOfferView sell = new FlipperOfferView(
            2, 5952, "Antidote++(4)", "sell", 9_488,
            1_212, 0, "active", 20, 0,
            9_277, 9_488, 10_100, 8_900, 9_589);

        RuneliteOverviewView.Opportunity activeBuy =
            OsrsFlipperSyncPanel.activeOfferOpportunity(
                5952, "buy", java.util.Collections.singletonList(buy));
        RuneliteOverviewView.Opportunity activeSell =
            OsrsFlipperSyncPanel.activeOfferOpportunity(
                5952, "sell", java.util.Collections.singletonList(sell));

        assertEquals(9_589, activeBuy.lowestSellPrice);
        assertEquals(9_589, activeSell.lowestSellPrice);
        assertEquals(10_100, activeSell.instantBuy);
        assertEquals(8_900, activeSell.instantSell);
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
