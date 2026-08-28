package com.osrsflipper.sync;

import java.util.Collections;

final class SelectedGeOpportunityResolver
{
    private static final String SELECTED_SETUP = "selected_setup";

    private SelectedGeOpportunityResolver()
    {
    }

    static Resolution resolve(
        FocusedGeItemResolver.EditorContext context,
        int itemId,
        String itemName,
        String side,
        RuneliteOverviewView.Opportunity scannerOpportunity,
        MarketPriceView market,
        LastTradePriceView priceTest,
        FlipperOfferView exactActiveOffer)
    {
        if (itemId <= 0 || (!"buy".equals(side) && !"sell".equals(side)))
        {
            return Resolution.empty();
        }
        if (context == FocusedGeItemResolver.EditorContext.EXISTING_OFFER && exactActiveOffer != null)
        {
            int liveInstantBuy = market != null && market.instantBuyPrice > 0
                ? market.instantBuyPrice
                : exactActiveOffer.wikiInstantBuyPrice;
            int liveInstantSell = market != null && market.instantSellPrice > 0
                ? market.instantSellPrice
                : exactActiveOffer.wikiInstantSellPrice;
            FlipperOfferView liveOffer = new FlipperOfferView(
                exactActiveOffer.slotNumber,
                exactActiveOffer.itemId,
                exactActiveOffer.itemName,
                exactActiveOffer.side,
                exactActiveOffer.price,
                exactActiveOffer.totalQuantity,
                exactActiveOffer.filledQuantity,
                exactActiveOffer.status,
                exactActiveOffer.startedAt,
                exactActiveOffer.endedAt,
                exactActiveOffer.suggestedBuyPrice,
                exactActiveOffer.suggestedSellPrice,
                liveInstantBuy,
                liveInstantSell,
                exactActiveOffer.lowestSellPrice);
            RuneliteOverviewView.Opportunity active = OsrsFlipperSyncPanel.activeOfferOpportunity(
                itemId,
                side,
                Collections.singletonList(liveOffer));
            return active == null ? Resolution.empty() : new Resolution(active);
        }
        if (context != FocusedGeItemResolver.EditorContext.NEW_SETUP)
        {
            return Resolution.empty();
        }

        boolean matchingMarket = market != null && market.itemId == itemId;
        int marketInstantBuy = matchingMarket ? market.instantBuyPrice : 0;
        int marketInstantSell = matchingMarket ? market.instantSellPrice : 0;
        if (scannerOpportunity == null && marketInstantBuy <= 0 && marketInstantSell <= 0)
        {
            return Resolution.empty();
        }

        int instantBuy = marketInstantBuy > 0
            ? marketInstantBuy
            : (scannerOpportunity == null ? 0 : scannerOpportunity.instantBuy);
        int instantSell = marketInstantSell > 0
            ? marketInstantSell
            : (scannerOpportunity == null ? 0 : scannerOpportunity.instantSell);
        int fallbackBuy = scannerOpportunity == null ? 0 : scannerOpportunity.buyPrice;
        int fallbackSell = scannerOpportunity == null ? 0 : scannerOpportunity.sellPrice;
        int buyPrice = FlipPriceResolver.buyPrice(instantSell, fallbackBuy, priceTest);
        int sellPrice = FlipPriceResolver.sellPrice(instantBuy, fallbackSell, priceTest);
        long priceUpdatedAt = !matchingMarket
            ? (scannerOpportunity == null ? 0 : scannerOpportunity.priceUpdatedAt)
            : Math.max(market.instantBuyAt, market.instantSellAt);
        String resolvedItemName = itemName == null ? "" : itemName.trim();
        if (resolvedItemName.isEmpty() && scannerOpportunity != null)
        {
            resolvedItemName = scannerOpportunity.itemName;
        }

        RuneliteOverviewView.Opportunity resolved = new RuneliteOverviewView.Opportunity(
            itemId,
            resolvedItemName,
            SELECTED_SETUP,
            buyPrice,
            sellPrice,
            instantBuy,
            instantSell,
            scannerOpportunity == null ? 0 : scannerOpportunity.expectedQuantity,
            scannerOpportunity == null ? 0 : scannerOpportunity.expectedProfit,
            scannerOpportunity == null ? 0 : scannerOpportunity.maximumQuantity,
            scannerOpportunity == null ? 0 : scannerOpportunity.maximumProfitPerHour,
            scannerOpportunity == null ? 0 : scannerOpportunity.maximumCycleProfit,
            priceUpdatedAt);
        return new Resolution(resolved);
    }

    static boolean isSelectedSetup(RuneliteOverviewView.Opportunity opportunity)
    {
        return opportunity != null && SELECTED_SETUP.equals(opportunity.ranking);
    }

    static final class Resolution
    {
        final RuneliteOverviewView.Opportunity opportunity;

        private Resolution(RuneliteOverviewView.Opportunity opportunity)
        {
            this.opportunity = opportunity;
        }

        static Resolution empty()
        {
            return new Resolution(null);
        }

        int price(String side)
        {
            if (opportunity == null)
            {
                return 0;
            }
            if ("buy".equals(side))
            {
                return opportunity.buyPrice;
            }
            return "sell".equals(side) ? opportunity.sellPrice : 0;
        }
    }
}
