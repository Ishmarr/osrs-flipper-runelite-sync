package com.osrsflipper.sync;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SellTargetPriceResolverTest
{
    @Test
    public void pricesOneGpBelowLiveInstantBuy()
    {
        MarketPriceView market = new MarketPriceView(101, 1_829, 1_753, 10, 9, 11);
        assertEquals(1_828, SellTargetPriceResolver.captured(market));
    }

    @Test
    public void freezesTheVisibleScannerInstantBuyAtOrderCreation()
    {
        MarketPriceView market = new MarketPriceView(101, 1_829, 1_753, 10, 9, 11);
        RuneliteOverviewView.Opportunity opportunity = opportunity(1_900, 1_850);
        assertEquals(1_828, SellTargetPriceResolver.provisional(market, opportunity));
        assertEquals(false, SellTargetPriceResolver.needsFreshCapture(opportunity));
    }

    @Test
    public void usesScannerInstantBuyAndNotScannerAdvicePrice()
    {
        RuneliteOverviewView.Opportunity opportunity = opportunity(1_900, 1_850);
        assertEquals(1_849, SellTargetPriceResolver.provisional(null, opportunity));
    }

    @Test
    public void requestsFreshPriceOnlyWhenNoScannerSnapshotExists()
    {
        MarketPriceView market = new MarketPriceView(101, 1_829, 1_753, 10, 9, 11);
        assertEquals(1_828, SellTargetPriceResolver.provisional(market, null));
        assertEquals(true, SellTargetPriceResolver.needsFreshCapture(null));
    }

    @Test
    public void returnsZeroWhenNoInstantBuyIsAvailable()
    {
        assertEquals(0, SellTargetPriceResolver.provisional(null, null));
    }

    @Test
    public void confirmedPriceTestOverridesWikiAndScanner()
    {
        MarketPriceView market = new MarketPriceView(101, 1_829, 1_753, 10, 9, 11);
        LastTradePriceView priceTest = new LastTradePriceView(101, 2_000, 1_700, 20, 21);
        assertEquals(1_999, SellTargetPriceResolver.provisional(
            market,
            opportunity(1_900, 1_850),
            priceTest));
    }

    @Test
    public void keepsTheStrongestSellReferenceAndOnlyRaisesACapturedTarget()
    {
        MarketPriceView market = new MarketPriceView(101, 1_900, 1_700, 10, 9, 11);
        LastTradePriceView lowerPriceTest = new LastTradePriceView(101, 1_850, 1_700, 20, 21);
        assertEquals(1_899, SellTargetPriceResolver.provisional(
            market,
            opportunity(1_920, 1_800),
            lowerPriceTest));
        assertEquals(2_099, SellTargetPriceResolver.raiseOnly(2_099, 1_899));
        assertEquals(2_199, SellTargetPriceResolver.raiseOnly(2_099, 2_199));
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
