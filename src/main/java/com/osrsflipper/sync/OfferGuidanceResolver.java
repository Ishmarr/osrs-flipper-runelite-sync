package com.osrsflipper.sync;

import java.util.Collection;
import java.util.Comparator;
import java.util.Set;

final class OfferGuidanceResolver
{
    private OfferGuidanceResolver()
    {
    }

    static Guidance buy(
        int actualOrderPrice,
        int currentSellCandidate,
        String itemName,
        Guidance previous,
        boolean continuingOffer)
    {
        Guidance safePrevious = previous == null ? Guidance.empty() : previous;
        int frozenBuyPrice = continuingOffer && safePrevious.buyPrice > 0
            ? safePrevious.buyPrice
            : Math.max(0, actualOrderPrice);
        Guidance repriced = reprice(
            "buy",
            frozenBuyPrice,
            currentSellCandidate,
            continuingOffer ? safePrevious : Guidance.empty());
        int lowestSellPrice = freezeLowestSellPrice(
            repriced.lowestSellPrice,
            repriced.buyPrice,
            itemName);
        return new Guidance(
            repriced.buyPrice,
            repriced.sellPrice,
            repriced.sourceBuyOfferId,
            lowestSellPrice);
    }

    static int freezeLowestSellPrice(int existingLowestSellPrice, int buyPrice, String itemName)
    {
        if (existingLowestSellPrice > 0)
        {
            return existingLowestSellPrice;
        }
        return SessionStatsTracker.calculateLowestBreakEvenSellPrice(
            buyPrice,
            itemName);
    }

    static int adoptServerLowestSellPrice(int localLowestSellPrice, int serverLowestSellPrice)
    {
        return serverLowestSellPrice > 0
            ? serverLowestSellPrice
            : Math.max(0, localLowestSellPrice);
    }

    static boolean continuesOfferLifecycle(boolean sameOfferShape, String previousStatus)
    {
        return sameOfferShape &&
            !"completed".equals(previousStatus) &&
            !"cancelled".equals(previousStatus);
    }

    /**
     * RuneLite normally reports Modify offer as a cancelled offer followed
     * directly by a replacement in the same GE slot. Under event coalescing the
     * intermediate CANCELLED observation can be absent. In both cases the lack
     * of an EMPTY state and the original/remaining quantity identify the direct
     * successor, whose frozen cycle guidance must be retained.
     */
    static boolean continuesCancelledReprice(
        boolean sameItemAndSide,
        String previousStatus,
        int previousPrice,
        int nextPrice,
        int previousTotalQuantity,
        int previousFilledQuantity,
        int nextTotalQuantity)
    {
        boolean replaceableStatus = "cancelled".equals(previousStatus) ||
            "active".equals(previousStatus) ||
            "partially_filled".equals(previousStatus);
        if (!sameItemAndSide || !replaceableStatus ||
            previousPrice <= 0 || nextPrice <= 0 ||
            previousTotalQuantity <= 0 || nextTotalQuantity <= 0)
        {
            return false;
        }

        int remainingQuantity = Math.max(
            0,
            previousTotalQuantity - Math.max(0, previousFilledQuantity));
        return (previousFilledQuantity <= 0 &&
                nextPrice != previousPrice &&
                nextTotalQuantity == previousTotalQuantity) ||
            (remainingQuantity > 0 && nextTotalQuantity == remainingQuantity);
    }

    static BuyCandidate frozenBuyCandidate(
        int slotNumber,
        int itemId,
        int filledQuantity,
        long startedAt,
        long lastEventAt,
        String offerId,
        String itemName,
        int buyPrice,
        int sellPrice,
        int lowestSellPrice)
    {
        int safeBuyPrice = Math.max(0, buyPrice);
        return new BuyCandidate(
            slotNumber,
            itemId,
            filledQuantity,
            startedAt,
            lastEventAt,
            offerId,
            safeBuyPrice,
            sellPrice,
            freezeLowestSellPrice(lowestSellPrice, safeBuyPrice, itemName));
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
            safePrevious.sourceBuyOfferId,
            safePrevious.lowestSellPrice);
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
            source == null ? "" : source.offerId,
            source == null ? 0 : source.lowestSellPrice);
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
        final int lowestSellPrice;

        Guidance(int buyPrice, int sellPrice, String sourceBuyOfferId)
        {
            this(buyPrice, sellPrice, sourceBuyOfferId, 0);
        }

        Guidance(int buyPrice, int sellPrice, String sourceBuyOfferId, int lowestSellPrice)
        {
            this.buyPrice = Math.max(0, buyPrice);
            this.sellPrice = Math.max(0, sellPrice);
            this.sourceBuyOfferId = sourceBuyOfferId == null ? "" : sourceBuyOfferId;
            this.lowestSellPrice = Math.max(0, lowestSellPrice);
        }

        static Guidance empty()
        {
            return new Guidance(0, 0, "", 0);
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
        final int lowestSellPrice;

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
            this(slotNumber, itemId, filledQuantity, startedAt, lastEventAt,
                offerId, buyPrice, sellPrice, 0);
        }

        BuyCandidate(
            int slotNumber,
            int itemId,
            int filledQuantity,
            long startedAt,
            long lastEventAt,
            String offerId,
            int buyPrice,
            int sellPrice,
            int lowestSellPrice)
        {
            this.slotNumber = slotNumber;
            this.itemId = itemId;
            this.filledQuantity = Math.max(0, filledQuantity);
            this.startedAt = Math.max(0, startedAt);
            this.lastEventAt = Math.max(0, lastEventAt);
            this.offerId = offerId == null ? "" : offerId;
            this.buyPrice = Math.max(0, buyPrice);
            this.sellPrice = Math.max(0, sellPrice);
            this.lowestSellPrice = Math.max(0, lowestSellPrice);
        }
    }
}
