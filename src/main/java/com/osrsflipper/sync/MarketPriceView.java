package com.osrsflipper.sync;

final class MarketPriceView
{
    final int itemId;
    final int instantBuyPrice;
    final int instantSellPrice;
    final long instantBuyAt;
    final long instantSellAt;
    final long fetchedAt;

    MarketPriceView(
        int itemId,
        int instantBuyPrice,
        int instantSellPrice,
        long instantBuyAt,
        long instantSellAt,
        long fetchedAt)
    {
        this.itemId = itemId;
        this.instantBuyPrice = Math.max(0, instantBuyPrice);
        this.instantSellPrice = Math.max(0, instantSellPrice);
        this.instantBuyAt = Math.max(0, instantBuyAt);
        this.instantSellAt = Math.max(0, instantSellAt);
        this.fetchedAt = Math.max(0, fetchedAt);
    }
}
