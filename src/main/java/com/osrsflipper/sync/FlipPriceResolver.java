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
        int lastSellPrice = priceTest == null ? 0 : Math.max(0, priceTest.lastSellPrice);
        if (lastSellPrice > 0)
        {
            return plusOne(lastSellPrice);
        }
        wikiInstantSell = Math.max(0, wikiInstantSell);
        if (wikiInstantSell > 0)
        {
            return plusOne(wikiInstantSell);
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
        int lastBuyPrice = priceTest == null ? 0 : Math.max(0, priceTest.lastBuyPrice);
        if (lastBuyPrice > 0)
        {
            return minusOne(lastBuyPrice);
        }
        wikiInstantBuy = Math.max(0, wikiInstantBuy);
        if (wikiInstantBuy > 0)
        {
            return minusOne(wikiInstantBuy);
        }
        return Math.max(0, fallbackSellPrice);
    }

    static int displayedBuyPrice(
        RuneliteOverviewView.Opportunity opportunity,
        LastTradePriceView priceTest)
    {
        return isFixedSelection(opportunity)
            ? Math.max(0, opportunity.buyPrice)
            : buyPrice(opportunity, priceTest);
    }

    static int displayedSellPrice(
        RuneliteOverviewView.Opportunity opportunity,
        LastTradePriceView priceTest)
    {
        return isFixedSelection(opportunity)
            ? Math.max(0, opportunity.sellPrice)
            : sellPrice(opportunity, priceTest);
    }

    static int editorPrice(
        String side,
        RuneliteOverviewView.Opportunity opportunity,
        MarketPriceView market,
        LastTradePriceView priceTest)
    {
        if (isFixedSelection(opportunity))
        {
            return "buy".equals(side)
                ? Math.max(0, opportunity.buyPrice)
                : ("sell".equals(side) ? Math.max(0, opportunity.sellPrice) : 0);
        }
        if ("buy".equals(side))
        {
            int wikiInstantSell = market != null && market.instantSellPrice > 0
                ? market.instantSellPrice
                : (opportunity == null ? 0 : opportunity.instantSell);
            int fallbackBuyPrice = opportunity == null ? 0 : opportunity.buyPrice;
            return buyPrice(wikiInstantSell, fallbackBuyPrice, priceTest);
        }
        if ("sell".equals(side))
        {
            int wikiInstantBuy = market != null && market.instantBuyPrice > 0
                ? market.instantBuyPrice
                : (opportunity == null ? 0 : opportunity.instantBuy);
            int fallbackSellPrice = opportunity == null ? 0 : opportunity.sellPrice;
            return sellPrice(wikiInstantBuy, fallbackSellPrice, priceTest);
        }
        return 0;
    }

    private static boolean isActiveOffer(RuneliteOverviewView.Opportunity opportunity)
    {
        return opportunity != null &&
            ("active_buy".equals(opportunity.ranking) ||
                "active_sell".equals(opportunity.ranking));
    }

    private static boolean isFixedSelection(RuneliteOverviewView.Opportunity opportunity)
    {
        return isActiveOffer(opportunity) ||
            SelectedGeOpportunityResolver.isSelectedSetup(opportunity) ||
            SelectedGeOpportunityResolver.isOpenFlipCycle(opportunity);
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
