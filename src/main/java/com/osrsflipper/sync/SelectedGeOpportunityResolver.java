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
                liveInstantSell);
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
        if (scannerOpportunity == null)
        {
            return Resolution.empty();
        }

        int instantBuy = market != null && market.instantBuyPrice > 0
            ? market.instantBuyPrice
            : scannerOpportunity.instantBuy;
        int instantSell = market != null && market.instantSellPrice > 0
            ? market.instantSellPrice
            : scannerOpportunity.instantSell;
        int fallbackBuy = scannerOpportunity.buyPrice;
        int fallbackSell = scannerOpportunity.sellPrice;
        int buyPrice = FlipPriceResolver.buyPrice(instantSell, fallbackBuy, priceTest);
        int sellPrice = FlipPriceResolver.sellPrice(instantBuy, fallbackSell, priceTest);
        long priceUpdatedAt = market == null
            ? scannerOpportunity.priceUpdatedAt
            : Math.max(market.instantBuyAt, market.instantSellAt);

        RuneliteOverviewView.Opportunity resolved = new RuneliteOverviewView.Opportunity(
            itemId,
            scannerOpportunity.itemName,
            SELECTED_SETUP,
            buyPrice,
            sellPrice,
            instantBuy,
            instantSell,
            scannerOpportunity.expectedQuantity,
            scannerOpportunity.expectedProfit,
            scannerOpportunity.maximumQuantity,
            scannerOpportunity.maximumProfitPerHour,
            scannerOpportunity.maximumCycleProfit,
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
