package com.osrsflipper.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class RuneliteOverviewView
{
    final List<Opportunity> expected;
    final List<Opportunity> hourly;
    final Opportunity focus;
    final PeriodStats today;
    final PeriodStats month;
    final PeriodStats total;
    final List<LastTradePriceView> priceTests;
    final CashBalance cash;
    final long generatedAt;

    RuneliteOverviewView(
        List<Opportunity> expected,
        List<Opportunity> hourly,
        PeriodStats today,
        PeriodStats month,
        PeriodStats total,
        long generatedAt)
    {
        this(expected, hourly, null, today, month, total, generatedAt);
    }

    RuneliteOverviewView(
        List<Opportunity> expected,
        List<Opportunity> hourly,
        Opportunity focus,
        PeriodStats today,
        PeriodStats month,
        PeriodStats total,
        long generatedAt)
    {
        this(expected, hourly, focus, today, month, total,
            Collections.emptyList(), CashBalance.empty(), generatedAt);
    }

    RuneliteOverviewView(
        List<Opportunity> expected,
        List<Opportunity> hourly,
        Opportunity focus,
        PeriodStats today,
        PeriodStats month,
        PeriodStats total,
        List<LastTradePriceView> priceTests,
        CashBalance cash,
        long generatedAt)
    {
        this.expected = immutable(expected);
        this.hourly = immutable(hourly);
        this.focus = focus;
        this.today = today == null ? PeriodStats.empty() : today;
        this.month = month == null ? PeriodStats.empty() : month;
        this.total = total == null ? PeriodStats.empty() : total;
        this.priceTests = Collections.unmodifiableList(new ArrayList<>(priceTests == null
            ? Collections.emptyList()
            : priceTests));
        this.cash = cash == null ? CashBalance.empty() : cash;
        this.generatedAt = Math.max(0, generatedAt);
    }

    static RuneliteOverviewView empty()
    {
        PeriodStats empty = PeriodStats.empty();
        return new RuneliteOverviewView(
            Collections.emptyList(), Collections.emptyList(), null, empty, empty, empty, 0);
    }

    Opportunity opportunityForItem(int itemId)
    {
        if (focus != null && focus.itemId == itemId)
        {
            return focus;
        }
        for (Opportunity opportunity : expected)
        {
            if (opportunity.itemId == itemId)
            {
                return opportunity;
            }
        }
        for (Opportunity opportunity : hourly)
        {
            if (opportunity.itemId == itemId)
            {
                return opportunity;
            }
        }
        return null;
    }

    int maximumQuantityForItem(int itemId)
    {
        Opportunity opportunity = opportunityForItem(itemId);
        return opportunity == null ? 0 : opportunity.effectiveMaximumQuantity();
    }

    PeriodStats statsFor(String period)
    {
        if ("month".equals(period))
        {
            return month;
        }
        if ("total".equals(period))
        {
            return total;
        }
        return today;
    }

    private static List<Opportunity> immutable(List<Opportunity> source)
    {
        return Collections.unmodifiableList(new ArrayList<>(source == null
            ? Collections.emptyList()
            : source));
    }

    private static List<PeriodItem> immutableItems(List<PeriodItem> source)
    {
        return Collections.unmodifiableList(new ArrayList<>(source == null
            ? Collections.emptyList()
            : source));
    }

    static final class Opportunity
    {
        final int itemId;
        final String itemName;
        final String ranking;
        final int buyPrice;
        final int sellPrice;
        final int instantBuy;
        final int instantSell;
        final int expectedQuantity;
        final long expectedProfit;
        final int maximumQuantity;
        final long maximumProfitPerHour;
        final long maximumCycleProfit;
        final long priceUpdatedAt;
        final int lowestSellPrice;
        final int officialBuyLimit;
        final int usedBuyLimit;
        final int remainingBuyLimit;
        private final boolean buyLimitAvailable;
        private final boolean quantityAvailable;

        Opportunity(
            int itemId,
            String itemName,
            String ranking,
            int buyPrice,
            int sellPrice,
            int instantBuy,
            int instantSell,
            int expectedQuantity,
            long expectedProfit,
            int maximumQuantity,
            long maximumProfitPerHour,
            long maximumCycleProfit,
            long priceUpdatedAt)
        {
            this(itemId, itemName, ranking, buyPrice, sellPrice, instantBuy,
                instantSell, expectedQuantity, expectedProfit, maximumQuantity,
                maximumProfitPerHour, maximumCycleProfit, priceUpdatedAt, 0,
                -1, -1, -1);
        }

        Opportunity(
            int itemId,
            String itemName,
            String ranking,
            int buyPrice,
            int sellPrice,
            int instantBuy,
            int instantSell,
            int expectedQuantity,
            long expectedProfit,
            int maximumQuantity,
            long maximumProfitPerHour,
            long maximumCycleProfit,
            long priceUpdatedAt,
            int lowestSellPrice)
        {
            this(itemId, itemName, ranking, buyPrice, sellPrice, instantBuy,
                instantSell, expectedQuantity, expectedProfit, maximumQuantity,
                maximumProfitPerHour, maximumCycleProfit, priceUpdatedAt,
                lowestSellPrice, -1, -1, -1);
        }

        Opportunity(
            int itemId,
            String itemName,
            String ranking,
            int buyPrice,
            int sellPrice,
            int instantBuy,
            int instantSell,
            int expectedQuantity,
            long expectedProfit,
            int maximumQuantity,
            long maximumProfitPerHour,
            long maximumCycleProfit,
            long priceUpdatedAt,
            int officialBuyLimit,
            int usedBuyLimit,
            int remainingBuyLimit)
        {
            this(itemId, itemName, ranking, buyPrice, sellPrice, instantBuy,
                instantSell, expectedQuantity, expectedProfit, maximumQuantity,
                maximumProfitPerHour, maximumCycleProfit, priceUpdatedAt, 0,
                officialBuyLimit, usedBuyLimit, remainingBuyLimit);
        }

        Opportunity(
            int itemId,
            String itemName,
            String ranking,
            int buyPrice,
            int sellPrice,
            int instantBuy,
            int instantSell,
            int expectedQuantity,
            long expectedProfit,
            int maximumQuantity,
            long maximumProfitPerHour,
            long maximumCycleProfit,
            long priceUpdatedAt,
            int lowestSellPrice,
            int officialBuyLimit,
            int usedBuyLimit,
            int remainingBuyLimit)
        {
            this.itemId = Math.max(0, itemId);
            this.itemName = itemName == null ? "" : itemName;
            this.ranking = ranking == null ? "" : ranking;
            this.buyPrice = Math.max(0, buyPrice);
            this.sellPrice = Math.max(0, sellPrice);
            this.instantBuy = Math.max(0, instantBuy);
            this.instantSell = Math.max(0, instantSell);
            this.expectedQuantity = Math.max(0, expectedQuantity);
            this.expectedProfit = Math.max(0, expectedProfit);
            this.quantityAvailable = maximumQuantity >= 0;
            this.maximumQuantity = Math.max(0, maximumQuantity);
            this.maximumProfitPerHour = Math.max(0, maximumProfitPerHour);
            this.maximumCycleProfit = Math.max(0, maximumCycleProfit);
            this.priceUpdatedAt = Math.max(0, priceUpdatedAt);
            this.lowestSellPrice = Math.max(0, lowestSellPrice);
            this.buyLimitAvailable = officialBuyLimit > 0 && usedBuyLimit >= 0;
            this.officialBuyLimit = this.buyLimitAvailable
                ? officialBuyLimit
                : 0;
            this.usedBuyLimit = this.buyLimitAvailable
                ? Math.max(0, usedBuyLimit)
                : 0;
            int calculatedRemaining = Math.max(0, this.officialBuyLimit - this.usedBuyLimit);
            this.remainingBuyLimit = this.buyLimitAvailable
                ? (remainingBuyLimit >= 0
                    ? Math.min(Math.max(0, remainingBuyLimit), calculatedRemaining)
                    : calculatedRemaining)
                : 0;
        }

        boolean hasBuyLimit()
        {
            return buyLimitAvailable;
        }

        boolean hasQuantity()
        {
            return quantityAvailable;
        }

        int effectiveMaximumQuantity()
        {
            return buyLimitAvailable
                ? Math.min(maximumQuantity, remainingBuyLimit)
                : maximumQuantity;
        }
    }

    static final class PeriodStats
    {
        final long realizedProfit;
        final double roiPercent;
        final long profitPerHour;
        final long geTax;
        final long tradingVolume;
        final int completedFlips;
        final List<PeriodItem> items;

        PeriodStats(
            long realizedProfit,
            double roiPercent,
            long profitPerHour,
            long geTax,
            long tradingVolume,
            int completedFlips)
        {
            this(realizedProfit, roiPercent, profitPerHour, geTax, tradingVolume,
                completedFlips, Collections.emptyList());
        }

        PeriodStats(
            long realizedProfit,
            double roiPercent,
            long profitPerHour,
            long geTax,
            long tradingVolume,
            int completedFlips,
            List<PeriodItem> items)
        {
            this.realizedProfit = realizedProfit;
            this.roiPercent = Double.isFinite(roiPercent) ? roiPercent : 0;
            this.profitPerHour = profitPerHour;
            this.geTax = Math.max(0, geTax);
            this.tradingVolume = Math.max(0, tradingVolume);
            this.completedFlips = Math.max(0, completedFlips);
            this.items = immutableItems(items);
        }

        static PeriodStats empty()
        {
            return new PeriodStats(0, 0, 0, 0, 0, 0);
        }
    }

    static final class PeriodItem
    {
        final int itemId;
        final String itemName;
        final long realizedProfit;
        final int completedFlips;

        PeriodItem(int itemId, String itemName, long realizedProfit, int completedFlips)
        {
            this.itemId = Math.max(0, itemId);
            this.itemName = itemName == null ? "" : itemName;
            this.realizedProfit = realizedProfit;
            this.completedFlips = Math.max(0, completedFlips);
        }
    }

    static final class CashBalance
    {
        final long available;
        final long reserved;
        final long total;
        final long updatedAt;

        CashBalance(long available, long reserved, long total, long updatedAt)
        {
            this.available = Math.max(0, available);
            this.reserved = Math.max(0, reserved);
            this.total = Math.max(this.available + this.reserved, Math.max(0, total));
            this.updatedAt = Math.max(0, updatedAt);
        }

        static CashBalance empty()
        {
            return new CashBalance(0, 0, 0, 0);
        }
    }
}
