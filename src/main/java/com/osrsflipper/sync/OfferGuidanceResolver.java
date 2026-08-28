package com.osrsflipper.sync;

import java.util.Collection;
import java.util.Comparator;
import java.util.Set;

final class OfferGuidanceResolver
{
    private OfferGuidanceResolver()
    {
    }

    static Guidance reprice(
        String side,
        int actualOrderPrice,
        int currentSellCandidate,
        Guidance previous)
    {
        Guidance safePrevious = previous == null ? Guidance.empty() : previous;
        int buyPrice = "buy".equals(side)
            ? Math.max(0, actualOrderPrice)
            : safePrevious.buyPrice;
        int sellCandidate = "sell".equals(side)
            ? Math.max(Math.max(0, actualOrderPrice), Math.max(0, currentSellCandidate))
            : Math.max(0, currentSellCandidate);
        int sellPrice = SellTargetPriceResolver.raiseOnly(
            safePrevious.sellPrice,
            sellCandidate);
        return new Guidance(
            buyPrice,
            sellPrice,
            safePrevious.sourceBuyOfferId);
    }

    static Guidance linkedSell(
        int actualSellOrderPrice,
        int currentSellCandidate,
        int fallbackBuyPrice,
        BuyCandidate source)
    {
        int buyPrice = source != null && source.buyPrice > 0
            ? source.buyPrice
            : Math.max(0, fallbackBuyPrice);
        int previousSellTarget = source == null ? 0 : source.sellPrice;
        int sellPrice = SellTargetPriceResolver.raiseOnly(
            previousSellTarget,
            Math.max(Math.max(0, actualSellOrderPrice), Math.max(0, currentSellCandidate)));
        return new Guidance(
            buyPrice,
            sellPrice,
            source == null ? "" : source.offerId);
    }

    static BuyCandidate selectBuyForSell(
        int sellSlotNumber,
        int itemId,
        int sellQuantity,
        long sellStartedAt,
        Collection<BuyCandidate> candidates,
        Set<String> alreadyLinkedOfferIds)
    {
        if (itemId <= 0 || sellQuantity <= 0 || candidates == null || candidates.isEmpty())
        {
            return null;
        }
        Set<String> linked = alreadyLinkedOfferIds == null
            ? java.util.Collections.emptySet()
            : alreadyLinkedOfferIds;
        Comparator<BuyCandidate> ranking = Comparator
            .comparingInt((BuyCandidate candidate) -> candidate.filledQuantity == sellQuantity ? 1 : 0)
            .thenComparingLong(candidate -> candidate.lastEventAt)
            .thenComparingLong(candidate -> candidate.startedAt)
            .thenComparingInt(candidate -> candidate.slotNumber == sellSlotNumber ? 1 : 0)
            .thenComparingInt(candidate -> -candidate.slotNumber)
            .thenComparing(candidate -> candidate.offerId);
        return candidates.stream()
            .filter(candidate -> candidate != null &&
                candidate.itemId == itemId &&
                candidate.filledQuantity >= sellQuantity &&
                candidate.buyPrice > 0 &&
                candidate.startedAt <= sellStartedAt &&
                !candidate.offerId.isEmpty() &&
                !linked.contains(candidate.offerId))
            .max(ranking)
            .orElse(null);
    }

    static final class Guidance
    {
        final int buyPrice;
        final int sellPrice;
        final String sourceBuyOfferId;

        Guidance(int buyPrice, int sellPrice, String sourceBuyOfferId)
        {
            this.buyPrice = Math.max(0, buyPrice);
            this.sellPrice = Math.max(0, sellPrice);
            this.sourceBuyOfferId = sourceBuyOfferId == null ? "" : sourceBuyOfferId;
        }

        static Guidance empty()
        {
            return new Guidance(0, 0, "");
        }
    }

    static final class BuyCandidate
    {
        final int slotNumber;
        final int itemId;
        final int filledQuantity;
        final long startedAt;
        final long lastEventAt;
        final String offerId;
        final int buyPrice;
        final int sellPrice;

        BuyCandidate(
            int slotNumber,
            int itemId,
            int filledQuantity,
            long startedAt,
            long lastEventAt,
            String offerId,
            int buyPrice,
            int sellPrice)
        {
            this.slotNumber = slotNumber;
            this.itemId = itemId;
            this.filledQuantity = Math.max(0, filledQuantity);
            this.startedAt = Math.max(0, startedAt);
            this.lastEventAt = Math.max(0, lastEventAt);
            this.offerId = offerId == null ? "" : offerId;
            this.buyPrice = Math.max(0, buyPrice);
            this.sellPrice = Math.max(0, sellPrice);
        }
    }
}
