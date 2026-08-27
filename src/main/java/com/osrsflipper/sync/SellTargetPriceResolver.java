package com.osrsflipper.sync;

final class SellTargetPriceResolver
{
    private SellTargetPriceResolver()
    {
    }

    static int provisional(
        MarketPriceView market,
        RuneliteOverviewView.Opportunity opportunity)
    {
        return provisional(market, opportunity, null);
    }

    static int provisional(
        MarketPriceView market,
        RuneliteOverviewView.Opportunity opportunity,
        LastTradePriceView priceTest)
    {
        if (priceTest != null && priceTest.lastBuyPrice > 0)
        {
            return Math.max(1, priceTest.lastBuyPrice - 1);
        }
        int wikiPrice = market == null ? 0 : Math.max(0, market.instantBuyPrice);
        if (wikiPrice <= 0)
        {
            wikiPrice = scannerSnapshot(opportunity);
        }
        return wikiPrice > 1 ? wikiPrice - 1 : wikiPrice;
    }

    static boolean needsFreshCapture(RuneliteOverviewView.Opportunity opportunity)
    {
        return scannerSnapshot(opportunity) <= 0;
    }

    static int captured(MarketPriceView market)
    {
        int wikiPrice = market == null ? 0 : Math.max(0, market.instantBuyPrice);
        return wikiPrice > 1 ? wikiPrice - 1 : wikiPrice;
    }

    private static int scannerSnapshot(RuneliteOverviewView.Opportunity opportunity)
    {
        return opportunity == null ? 0 : Math.max(0, opportunity.instantBuy);
    }
}
