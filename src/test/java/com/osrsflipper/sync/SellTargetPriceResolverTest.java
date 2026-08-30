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
    public void higherOverviewInstabuyWinsOverLowerCachedMarket()
    {
        MarketPriceView market = new MarketPriceView(101, 1_829, 1_753, 10, 9, 11);
        RuneliteOverviewView.Opportunity opportunity = opportunity(1_900, 1_850);
        assertEquals(1_849, SellTargetPriceResolver.provisional(market, opportunity));
        assertEquals(1_849, SellTargetPriceResolver.liveWikiRaiseCandidate(market, opportunity));
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
    public void laterLastBuyCannotRaiseAnOpenFlip()
    {
        MarketPriceView market = new MarketPriceView(101, 1_000, 990, 10, 9, 11);
        LastTradePriceView initialPriceTest = new LastTradePriceView(101, 1_003, 938, 20, 21);
        LastTradePriceView laterPriceTest = new LastTradePriceView(101, 1_100, 938, 30, 31);

        int initialTarget = SellTargetPriceResolver.initial(
            market,
            opportunity(1_002, 1_000),
            initialPriceTest);
        assertEquals(1_002, initialTarget);
        assertEquals(1_099, SellTargetPriceResolver.provisional(
            market,
            opportunity(1_002, 1_000),
            laterPriceTest));

        int wikiOnlyCandidate = SellTargetPriceResolver.liveWikiRaiseCandidate(
            market,
            opportunity(1_002, 1_000));
        assertEquals(999, wikiOnlyCandidate);
        assertEquals(1_002, SellTargetPriceResolver.raiseOnly(initialTarget, wikiOnlyCandidate));
    }

    @Test
    public void seaTurtleWikiRiseCanOnlyRaiseTheSellTarget()
    {
        LastTradePriceView priceTest = new LastTradePriceView(101, 1_003, 938, 20, 21);

        int initialTarget = SellTargetPriceResolver.initial(
            new MarketPriceView(101, 1_000, 990, 30, 29, 31),
            opportunity(1_002, 1_000),
            priceTest);
        int raisedCandidate = SellTargetPriceResolver.liveWikiRaiseCandidate(
            new MarketPriceView(101, 1_014, 990, 34, 29, 35),
            opportunity(1_002, 1_014));
        int lowerCandidate = SellTargetPriceResolver.liveWikiRaiseCandidate(
            new MarketPriceView(101, 995, 980, 36, 35, 37),
            opportunity(1_002, 995));

        assertEquals(1_002, initialTarget);
        assertEquals(1_013, raisedCandidate);
        assertEquals(1_013, SellTargetPriceResolver.raiseOnly(initialTarget, raisedCandidate));
        assertEquals(1_020, SellTargetPriceResolver.raiseOnly(1_020, raisedCandidate));
        assertEquals(1_013, SellTargetPriceResolver.raiseOnly(1_013, lowerCandidate));
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
