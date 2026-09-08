package com.osrsflipper.sync;

final class MarketPriceView
{
    final int itemId;
    final int instantBuyPrice;
    final int instantSellPrice;
    final long instantBuyAt;
    final long instantSellAt;
    final long fetchedAt;
    final boolean instantBuyTimeExact;
    final boolean instantSellTimeExact;

    MarketPriceView(
        int itemId,
        int instantBuyPrice,
        int instantSellPrice,
        long instantBuyAt,
        long instantSellAt,
        long fetchedAt)
    {
        this(itemId, instantBuyPrice, instantSellPrice, instantBuyAt, instantSellAt, fetchedAt, true, true);
    }

    MarketPriceView(int itemId, int instantBuyPrice, int instantSellPrice,
        long instantBuyAt, long instantSellAt, long fetchedAt,
        boolean instantBuyTimeExact, boolean instantSellTimeExact)
    {
        this.itemId = itemId;
        this.instantBuyPrice = Math.max(0, instantBuyPrice);
        this.instantSellPrice = Math.max(0, instantSellPrice);
        this.instantBuyAt = Math.max(0, instantBuyAt);
        this.instantSellAt = Math.max(0, instantSellAt);
        this.fetchedAt = Math.max(0, fetchedAt);
        this.instantBuyTimeExact = instantBuyTimeExact;
        this.instantSellTimeExact = instantSellTimeExact;
    }

    /** Keeps observed transactions across sources without inventing a Wiki check time. */
    static MarketPriceView accept(MarketPriceView previous, MarketPriceView incoming, long now)
    {
        boolean high = valid(incoming.instantBuyPrice, incoming.instantBuyAt, now);
        boolean low = valid(incoming.instantSellPrice, incoming.instantSellAt, now);
        if (!high && !low) return null;
        boolean useHigh = high && (previous == null || previous.instantBuyPrice <= 0 || newer(
            incoming.instantBuyAt, incoming.instantBuyTimeExact, previous.instantBuyAt, previous.instantBuyTimeExact));
        boolean useLow = low && (previous == null || previous.instantSellPrice <= 0 || newer(
            incoming.instantSellAt, incoming.instantSellTimeExact, previous.instantSellAt, previous.instantSellTimeExact));
        return new MarketPriceView(incoming.itemId,
            useHigh ? incoming.instantBuyPrice : previous == null ? 0 : previous.instantBuyPrice,
            useLow ? incoming.instantSellPrice : previous == null ? 0 : previous.instantSellPrice,
            useHigh ? incoming.instantBuyAt : previous == null ? 0 : previous.instantBuyAt,
            useLow ? incoming.instantSellAt : previous == null ? 0 : previous.instantSellAt,
            incoming.fetchedAt > 0 ? incoming.fetchedAt : previous == null ? 0 : previous.fetchedAt,
            useHigh ? incoming.instantBuyTimeExact : previous != null && previous.instantBuyTimeExact,
            useLow ? incoming.instantSellTimeExact : previous != null && previous.instantSellTimeExact);
    }

    private static boolean valid(int price, long at, long now)
    {
        return price > 0 && at > 0 && at <= now + 600;
    }

    private static boolean newer(long incomingAt, boolean incomingExact, long previousAt, boolean previousExact)
    {
        // An aggregate max(highTime, lowTime) is not proof of either side's age.
        // Prefer actual side times; otherwise compare like-for-like timestamps.
        return incomingExact != previousExact ? incomingExact : incomingAt >= previousAt;
    }
}
