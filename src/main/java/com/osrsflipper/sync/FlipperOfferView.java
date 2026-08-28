package com.osrsflipper.sync;

final class FlipperOfferView
{
    final int slotNumber;
    final int itemId;
    final String itemName;
    final String side;
    final int price;
    final int totalQuantity;
    final int filledQuantity;
    final String status;
    final long startedAt;
    final long endedAt;
    final int suggestedBuyPrice;
    final int suggestedSellPrice;
    final int wikiInstantBuyPrice;
    final int wikiInstantSellPrice;
    final int lowestSellPrice;

    FlipperOfferView(
        int slotNumber,
        int itemId,
        String itemName,
        String side,
        int price,
        int totalQuantity,
        int filledQuantity,
        String status,
        long startedAt,
        long endedAt,
        int suggestedBuyPrice,
        int suggestedSellPrice,
        int wikiInstantBuyPrice,
        int wikiInstantSellPrice)
    {
        this(slotNumber, itemId, itemName, side, price, totalQuantity,
            filledQuantity, status, startedAt, endedAt, suggestedBuyPrice,
            suggestedSellPrice, wikiInstantBuyPrice, wikiInstantSellPrice, 0);
    }

    FlipperOfferView(
        int slotNumber,
        int itemId,
        String itemName,
        String side,
        int price,
        int totalQuantity,
        int filledQuantity,
        String status,
        long startedAt,
        long endedAt,
        int suggestedBuyPrice,
        int suggestedSellPrice,
        int wikiInstantBuyPrice,
        int wikiInstantSellPrice,
        int lowestSellPrice)
    {
        this.slotNumber = slotNumber;
        this.itemId = itemId;
        this.itemName = itemName;
        this.side = side;
        this.price = price;
        this.totalQuantity = totalQuantity;
        this.filledQuantity = filledQuantity;
        this.status = status;
        this.startedAt = startedAt;
        this.endedAt = Math.max(0, endedAt);
        this.suggestedBuyPrice = Math.max(0, suggestedBuyPrice);
        this.suggestedSellPrice = Math.max(0, suggestedSellPrice);
        this.wikiInstantBuyPrice = Math.max(0, wikiInstantBuyPrice);
        this.wikiInstantSellPrice = Math.max(0, wikiInstantSellPrice);
        this.lowestSellPrice = Math.max(0, lowestSellPrice);
    }
}
