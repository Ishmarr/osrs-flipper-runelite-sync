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

    FlipperOfferView(
        int slotNumber,
        int itemId,
        String itemName,
        String side,
        int price,
        int totalQuantity,
        int filledQuantity,
        String status,
        long startedAt)
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
    }
}
