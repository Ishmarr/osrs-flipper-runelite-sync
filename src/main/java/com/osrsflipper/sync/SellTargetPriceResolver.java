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
        int livePrice = captured(market);
        if (livePrice > 0)
        {
            return livePrice;
        }
        return opportunity == null ? 0 : Math.max(0, opportunity.instantBuy);
    }

    static int captured(MarketPriceView market)
    {
        return market == null ? 0 : Math.max(0, market.instantBuyPrice);
    }
}
