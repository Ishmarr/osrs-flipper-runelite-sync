package com.osrsflipper.sync;

/** Already fetched Worker limits, repriced locally for the selected GE setup. */
final class QuantityCapacity
{
    final long cashAvailable;
    final double buyVolumePerHour;
    final double sellVolumePerHour;
    final long guidePrice;

    QuantityCapacity(long cashAvailable, double buyVolumePerHour, double sellVolumePerHour, long guidePrice)
    {
        this.cashAvailable = cashAvailable;
        this.buyVolumePerHour = buyVolumePerHour;
        this.sellVolumePerHour = sellVolumePerHour;
        this.guidePrice = guidePrice;
    }

    Estimate atPrices(int itemId, int buyPrice, int sellPrice, int remainingLimit, Long currentCash)
    {
        if (cashAvailable < 0 || !Double.isFinite(buyVolumePerHour) || buyVolumePerHour < 0 ||
            !Double.isFinite(sellVolumePerHour) || sellVolumePerHour < 0 || guidePrice <= 0 ||
            remainingLimit < 0 || buyPrice <= 0 || sellPrice <= 0)
        {
            return new Estimate(-1, 0, 0, "Capaciteit niet beschikbaar");
        }
        if (remainingLimit == 0) return new Estimate(0, 0, 0, "Buy limit volledig gebruikt");
        // The Worker applies its guide rule to the price references, before
        // the existing +1 buy / -1 sell tick adjustment.
        if ((long) buyPrice - 1 > guidePrice || (long) sellPrice + 1 > guidePrice)
            return new Estimate(0, 0, 0, "Prijs boven GE-richtprijs");
        long profit = GeTax.calculateProfitPerItem(buyPrice, sellPrice, itemId);
        if (profit <= 0) return new Estimate(0, 0, 0, "Geen winst na GE-tax");
        long available = Math.max(0, currentCash == null ? cashAvailable : currentCash);
        long affordable = available / buyPrice;
        if (affordable == 0) return new Estimate(0, 0, 0, "Onvoldoende beschikbare cash");
        int buyCapacity = (int) Math.min(Integer.MAX_VALUE, Math.floor(buyVolumePerHour));
        int sellCapacity = (int) Math.min(Integer.MAX_VALUE, Math.floor(sellVolumePerHour * 2));
        int quantity = (int) Math.min(Math.min(remainingLimit, affordable), Math.min(buyCapacity, sellCapacity));
        if (quantity == 0) return new Estimate(0, 0, 0, "Onvoldoende recent handelsvolume");
        long total = profit * quantity;
        long hourly = Math.round(total / (1 + quantity / sellVolumePerHour));
        return new Estimate(quantity, total, hourly, "");
    }

    static final class Estimate
    {
        final int quantity;
        final long cycleProfit;
        final long profitPerHour;
        final String reason;

        Estimate(int quantity, long cycleProfit, long profitPerHour, String reason)
        {
            this.quantity = quantity;
            this.cycleProfit = cycleProfit;
            this.profitPerHour = profitPerHour;
            this.reason = reason;
        }
    }
}
