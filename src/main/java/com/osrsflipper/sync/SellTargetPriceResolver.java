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
        int wikiPrice = wikiInstantBuy(market, opportunity);
        int fallback = opportunity == null ? 0 : Math.max(0, opportunity.sellPrice);
        return FlipPriceResolver.sellPrice(wikiPrice, fallback, priceTest);
    }

    /**
     * Captures the sell target when a new flip cycle starts.
     *
     * A personal Last buy price remains the primary initial anchor. The live
     * Wiki target is included here as well, so a Wiki price that is already
     * higher at the first observation does not need a second refresh before it
     * becomes visible.
     */
    static int initial(
        MarketPriceView market,
        RuneliteOverviewView.Opportunity opportunity,
        LastTradePriceView priceTest)
    {
        return raiseOnly(
            provisional(market, opportunity, priceTest),
            liveWikiRaiseCandidate(market, opportunity));
    }

    /**
     * Returns the sole automatic raise candidate for an existing flip cycle.
     *
     * Last buy is deliberately absent: a later personal price test may never
     * rewrite a running cycle. Only Wiki instabuy minus one GP can raise the
     * already stored sell target; the caller applies this candidate raise-only.
     */
    static int liveWikiRaiseCandidate(
        MarketPriceView market,
        RuneliteOverviewView.Opportunity opportunity)
    {
        return oneBelow(wikiInstantBuy(market, opportunity));
    }

    static boolean needsFreshCapture(RuneliteOverviewView.Opportunity opportunity)
    {
        return scannerSnapshot(opportunity) <= 0;
    }

    static int captured(MarketPriceView market)
    {
        int wikiPrice = market == null ? 0 : Math.max(0, market.instantBuyPrice);
        return oneBelow(wikiPrice);
    }

    static int raiseOnly(int currentPrice, int candidatePrice)
    {
        return Math.max(Math.max(0, currentPrice), Math.max(0, candidatePrice));
    }

    private static int scannerSnapshot(RuneliteOverviewView.Opportunity opportunity)
    {
        return opportunity == null ? 0 : Math.max(0, opportunity.instantBuy);
    }

    static int wikiInstantBuy(
        MarketPriceView market,
        RuneliteOverviewView.Opportunity opportunity)
    {
        int marketPrice = market == null ? 0 : Math.max(0, market.instantBuyPrice);
        return Math.max(marketPrice, scannerSnapshot(opportunity));
    }

    private static int oneBelow(int value)
    {
        int safeValue = Math.max(0, value);
        return safeValue > 1 ? safeValue - 1 : safeValue;
    }
}
