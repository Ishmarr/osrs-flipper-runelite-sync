package com.osrsflipper.sync;

final class LastTradePriceView
{
    final int itemId;
    final int lastBuyPrice;
    final int lastSellPrice;
    final long lastBuyAt;
    final long lastSellAt;

    LastTradePriceView(
        int itemId,
        int lastBuyPrice,
        int lastSellPrice,
        long lastBuyAt,
        long lastSellAt)
    {
        this.itemId = Math.max(0, itemId);
        this.lastBuyPrice = Math.max(0, lastBuyPrice);
        this.lastSellPrice = Math.max(0, lastSellPrice);
        this.lastBuyAt = Math.max(0, lastBuyAt);
        this.lastSellAt = Math.max(0, lastSellAt);
    }
}
