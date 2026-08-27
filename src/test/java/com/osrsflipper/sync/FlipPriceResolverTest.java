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
    public void buyUsesTheHighestWikiOrPriceTestSellReference()
    {
        LastTradePriceView priceTest = new LastTradePriceView(101, 1_900, 1_700, 20, 21);
        assertEquals(1_754, FlipPriceResolver.buyPrice(opportunity(), priceTest));
        assertEquals(1_899, FlipPriceResolver.sellPrice(opportunity(), priceTest));

        LastTradePriceView higherPriceTest = new LastTradePriceView(101, 1_900, 1_800, 20, 21);
        assertEquals(1_801, FlipPriceResolver.buyPrice(opportunity(), higherPriceTest));
    }

    private static RuneliteOverviewView.Opportunity opportunity()
    {
        return new RuneliteOverviewView.Opportunity(
            101, "Test item", "cycle_profit",
            1_600, 2_000, 1_829, 1_753,
            10, 1_000, 10, 500, 1_000, 123);
    }
}
