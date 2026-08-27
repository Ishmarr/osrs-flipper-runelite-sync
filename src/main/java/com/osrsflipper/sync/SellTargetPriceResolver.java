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
        int scannerPrice = scannerSnapshot(opportunity);
        if (scannerPrice > 0)
        {
            return scannerPrice;
        }
        return captured(market);
    }

    static boolean needsFreshCapture(RuneliteOverviewView.Opportunity opportunity)
    {
        return scannerSnapshot(opportunity) <= 0;
    }

    static int captured(MarketPriceView market)
    {
        return market == null ? 0 : Math.max(0, market.instantBuyPrice);
    }

    private static int scannerSnapshot(RuneliteOverviewView.Opportunity opportunity)
    {
        return opportunity == null ? 0 : Math.max(0, opportunity.instantBuy);
    }
}
