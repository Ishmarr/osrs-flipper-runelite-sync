package com.osrsflipper.sync;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FlipPriceResolverTest
{
    @Test
    public void usesWikiPricesUntilAConfirmedPriceTestExists()
    {
        RuneliteOverviewView.Opportunity opportunity = opportunity();
        assertEquals(1_754, FlipPriceResolver.buyPrice(opportunity, null));
        assertEquals(1_828, FlipPriceResolver.sellPrice(opportunity, null));
    }

    @Test
    public void pricesUseTheStrongestWikiOrPriceTestReference()
    {
        LastTradePriceView priceTest = new LastTradePriceView(101, 1_900, 1_700, 20, 21);
        assertEquals(1_754, FlipPriceResolver.buyPrice(opportunity(), priceTest));
        assertEquals(1_899, FlipPriceResolver.sellPrice(opportunity(), priceTest));

        LastTradePriceView higherPriceTest = new LastTradePriceView(101, 1_900, 1_800, 20, 21);
        assertEquals(1_801, FlipPriceResolver.buyPrice(opportunity(), higherPriceTest));

        LastTradePriceView lowerBuyPriceTest = new LastTradePriceView(101, 1_700, 1_800, 20, 21);
        assertEquals(1_828, FlipPriceResolver.sellPrice(opportunity(), lowerBuyPriceTest));
    }

    @Test
    public void rawMarketPricesUseTheSameRulesAsOpportunityCards()
    {
        LastTradePriceView priceTest = new LastTradePriceView(101, 1_900, 1_800, 20, 21);
        assertEquals(1_801, FlipPriceResolver.buyPrice(1_750, 1_700, priceTest));
        assertEquals(1_899, FlipPriceResolver.sellPrice(1_850, 1_920, priceTest));

        assertEquals(1_751, FlipPriceResolver.buyPrice(1_750, 1_700, null));
        assertEquals(1_849, FlipPriceResolver.sellPrice(1_850, 1_920, null));
    }

    @Test
    public void editorUsesTheFreshMarketPriceForANewOffer()
    {
        RuneliteOverviewView.Opportunity overview = opportunity();
        MarketPriceView newerButDifferentMarket = new MarketPriceView(
            101, 2_500, 2_400, 30, 31, 32);
        LastTradePriceView priceTest = new LastTradePriceView(101, 1_900, 1_800, 20, 21);

        assertEquals(2_401,
            FlipPriceResolver.editorPrice("buy", overview, newerButDifferentMarket, priceTest));
        assertEquals(2_499,
            FlipPriceResolver.editorPrice("sell", overview, newerButDifferentMarket, priceTest));
    }

    @Test
    public void editorKeepsTheFrozenPlanForAnActiveOffer()
    {
        RuneliteOverviewView.Opportunity active = new RuneliteOverviewView.Opportunity(
            101, "Test item", "active_buy",
            1_700, 1_900, 2_500, 2_400,
            10, 1_000, 10, 500, 1_000, 123);
        MarketPriceView market = new MarketPriceView(101, 2_600, 2_300, 30, 31, 32);

        assertEquals(1_700, FlipPriceResolver.editorPrice("buy", active, market, null));
        assertEquals(1_900, FlipPriceResolver.editorPrice("sell", active, market, null));
    }

    @Test
    public void panelMapsPersonalFillPricesToTheMatchingBuyAndSellRows()
    {
        LastTradePriceView priceTest = new LastTradePriceView(
            101, 9_141, 8_000, 20, 21);

        assertEquals(9_141,
            FlipPriceResolver.displayedBuyPrice(opportunity(), priceTest));
        assertEquals(8_000,
            FlipPriceResolver.displayedSellPrice(opportunity(), priceTest));
    }

    @Test
    public void panelFallsBackPerSideWhenTheMatchingPersonalPriceIsMissing()
    {
        LastTradePriceView onlySell = new LastTradePriceView(
            101, 0, 8_000, 0, 21);
        LastTradePriceView onlyBuy = new LastTradePriceView(
            101, 9_141, 0, 20, 0);

        assertEquals(1_754,
            FlipPriceResolver.displayedBuyPrice(opportunity(), onlySell));
        assertEquals(8_000,
            FlipPriceResolver.displayedSellPrice(opportunity(), onlySell));
        assertEquals(9_141,
            FlipPriceResolver.displayedBuyPrice(opportunity(), onlyBuy));
        assertEquals(1_828,
            FlipPriceResolver.displayedSellPrice(opportunity(), onlyBuy));
    }

    private static RuneliteOverviewView.Opportunity opportunity()
    {
        return new RuneliteOverviewView.Opportunity(
            101, "Test item", "cycle_profit",
            1_600, 2_000, 1_829, 1_753,
            10, 1_000, 10, 500, 1_000, 123);
    }
}
