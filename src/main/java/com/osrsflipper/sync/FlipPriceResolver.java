package com.osrsflipper.sync;

final class FlipPriceResolver
{
    private FlipPriceResolver()
    {
    }

    static int buyPrice(
        RuneliteOverviewView.Opportunity opportunity,
        LastTradePriceView priceTest)
    {
        if (priceTest != null && priceTest.lastSellPrice > 0)
        {
            return plusOne(priceTest.lastSellPrice);
        }
        if (opportunity != null && opportunity.instantSell > 0)
        {
            return plusOne(opportunity.instantSell);
        }
        return opportunity == null ? 0 : Math.max(0, opportunity.buyPrice);
    }

    static int sellPrice(
        RuneliteOverviewView.Opportunity opportunity,
        LastTradePriceView priceTest)
    {
        if (priceTest != null && priceTest.lastBuyPrice > 0)
        {
            return minusOne(priceTest.lastBuyPrice);
        }
        if (opportunity != null && opportunity.instantBuy > 0)
        {
            return minusOne(opportunity.instantBuy);
        }
        return opportunity == null ? 0 : Math.max(0, opportunity.sellPrice);
    }

    private static int plusOne(int value)
    {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, value) + 1;
    }

    private static int minusOne(int value)
    {
        return value > 1 ? value - 1 : Math.max(0, value);
    }
}
