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
        int wikiInstantSell = opportunity == null ? 0 : Math.max(0, opportunity.instantSell);
        int fallbackBuyPrice = opportunity == null ? 0 : Math.max(0, opportunity.buyPrice);
        return buyPrice(wikiInstantSell, fallbackBuyPrice, priceTest);
    }

    static int buyPrice(
        int wikiInstantSell,
        int fallbackBuyPrice,
        LastTradePriceView priceTest)
    {
        wikiInstantSell = Math.max(0, wikiInstantSell);
        int lastSellPrice = priceTest == null ? 0 : Math.max(0, priceTest.lastSellPrice);
        int strongestSellReference = Math.max(wikiInstantSell, lastSellPrice);
        if (strongestSellReference > 0)
        {
            return plusOne(strongestSellReference);
        }
        return Math.max(0, fallbackBuyPrice);
    }

    static int sellPrice(
        RuneliteOverviewView.Opportunity opportunity,
        LastTradePriceView priceTest)
    {
        int wikiInstantBuy = opportunity == null ? 0 : Math.max(0, opportunity.instantBuy);
        int fallbackSellPrice = opportunity == null ? 0 : Math.max(0, opportunity.sellPrice);
        return sellPrice(wikiInstantBuy, fallbackSellPrice, priceTest);
    }

    static int sellPrice(
        int wikiInstantBuy,
        int fallbackSellPrice,
        LastTradePriceView priceTest)
    {
        wikiInstantBuy = Math.max(0, wikiInstantBuy);
        int lastBuyPrice = priceTest == null ? 0 : Math.max(0, priceTest.lastBuyPrice);
        int strongestBuyReference = Math.max(wikiInstantBuy, lastBuyPrice);
        if (strongestBuyReference > 0)
        {
            return minusOne(strongestBuyReference);
        }
        return Math.max(0, fallbackSellPrice);
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
