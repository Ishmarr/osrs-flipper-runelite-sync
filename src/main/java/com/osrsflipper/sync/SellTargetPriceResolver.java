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
        int wikiPrice = market == null ? 0 : Math.max(0, market.instantBuyPrice);
        if (wikiPrice <= 0)
        {
            wikiPrice = scannerSnapshot(opportunity);
        }
        int fallback = opportunity == null ? 0 : Math.max(0, opportunity.sellPrice);
        return FlipPriceResolver.sellPrice(wikiPrice, fallback, priceTest);
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

    static int raiseOnly(int currentPrice, int candidatePrice)
    {
        return Math.max(Math.max(0, currentPrice), Math.max(0, candidatePrice));
    }

    private static int scannerSnapshot(RuneliteOverviewView.Opportunity opportunity)
    {
        return opportunity == null ? 0 : Math.max(0, opportunity.instantBuy);
    }
}
