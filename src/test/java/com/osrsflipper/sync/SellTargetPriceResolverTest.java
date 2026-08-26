package com.osrsflipper.sync;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SellTargetPriceResolverTest
{
    @Test
    public void usesExactLiveInstantBuyWithoutSubtractingOneGp()
    {
        MarketPriceView market = new MarketPriceView(101, 1_829, 1_753, 10, 9, 11);
        assertEquals(1_829, SellTargetPriceResolver.captured(market));
    }

    @Test
    public void prefersLiveInstantBuyOverScannerSnapshot()
    {
        MarketPriceView market = new MarketPriceView(101, 1_829, 1_753, 10, 9, 11);
        RuneliteOverviewView.Opportunity opportunity = opportunity(1_900, 1_850);
        assertEquals(1_829, SellTargetPriceResolver.provisional(market, opportunity));
    }

    @Test
    public void fallsBackToScannerInstantBuyAndNotScannerAdvicePrice()
    {
        RuneliteOverviewView.Opportunity opportunity = opportunity(1_900, 1_850);
        assertEquals(1_850, SellTargetPriceResolver.provisional(null, opportunity));
    }

    @Test
    public void returnsZeroWhenNoInstantBuyIsAvailable()
    {
        assertEquals(0, SellTargetPriceResolver.provisional(null, null));
    }

    private static RuneliteOverviewView.Opportunity opportunity(int sellPrice, int instantBuy)
    {
        return new RuneliteOverviewView.Opportunity(
            101,
            "Test item",
            "expected",
            1_700,
            sellPrice,
            instantBuy,
            1_650,
            100,
            10_000,
            100,
            1_000,
            10_000,
            60);
    }
}
