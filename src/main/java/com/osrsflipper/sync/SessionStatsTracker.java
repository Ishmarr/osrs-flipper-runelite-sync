package com.osrsflipper.sync;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

final class SessionStatsTracker
{
    private final Map<Integer, Deque<InventoryLot>> inventory = new LinkedHashMap<>();
    private final Map<Integer, MutableItemStats> items = new LinkedHashMap<>();
    private long startedAt;
    private long invested;
    private long grossRevenue;
    private long taxPaid;
    private long matchedCost;
    private long realizedProfit;
    private int boughtQuantity;
    private int soldQuantity;
    private int matchedQuantity;
    private int completedBuyOffers;
    private int completedSellOffers;

    SessionStatsTracker()
    {
        reset();
    }

    void reset()
    {
        inventory.clear();
        items.clear();
        startedAt = Instant.now().getEpochSecond();
        invested = 0;
        grossRevenue = 0;
        taxPaid = 0;
        matchedCost = 0;
        realizedProfit = 0;
        boughtQuantity = 0;
        soldQuantity = 0;
        matchedQuantity = 0;
        completedBuyOffers = 0;
        completedSellOffers = 0;
    }

    void recordTransition(
        int itemId,
        String itemName,
        String side,
        int previousFilled,
        int previousSpent,
        String previousStatus,
        int filled,
        int spent,
        String status,
        int fallbackPrice)
    {
        int quantity = Math.max(0, filled - Math.max(0, previousFilled));
        long amount = Math.max(0L, (long) spent - Math.max(0, previousSpent));
        if (quantity > 0 && amount <= 0)
        {
            amount = (long) quantity * Math.max(0, fallbackPrice);
        }

        if ("buy".equals(side) && quantity > 0)
        {
            recordBuy(itemId, itemName, quantity, amount);
        }
        else if ("sell".equals(side) && quantity > 0)
        {
            recordSell(itemId, itemName, quantity, amount);
        }

        boolean newlyCompleted = "completed".equals(status) && !"completed".equals(previousStatus);
        if (newlyCompleted && "buy".equals(side))
        {
            completedBuyOffers++;
        }
        else if (newlyCompleted && "sell".equals(side))
        {
            completedSellOffers++;
        }
    }

    SessionStatsSnapshot snapshot()
    {
        Map<Integer, SessionItemStats> immutableItems = new LinkedHashMap<>();
        for (Map.Entry<Integer, MutableItemStats> entry : items.entrySet())
        {
            MutableItemStats value = entry.getValue();
            immutableItems.put(entry.getKey(), new SessionItemStats(
                entry.getKey(),
                value.itemName,
                value.boughtQuantity,
                value.soldQuantity,
                value.matchedQuantity,
                value.profit));
        }
        return new SessionStatsSnapshot(
            startedAt,
            invested,
            grossRevenue,
            taxPaid,
            matchedCost,
            realizedProfit,
            boughtQuantity,
            soldQuantity,
            matchedQuantity,
            completedBuyOffers,
            completedSellOffers,
            immutableItems);
    }

    private void recordBuy(int itemId, String itemName, int quantity, long cost)
    {
        inventory.computeIfAbsent(itemId, ignored -> new ArrayDeque<>())
            .addLast(new InventoryLot(quantity, Math.max(0, cost)));
        MutableItemStats item = item(itemId, itemName);
        item.boughtQuantity += quantity;
        boughtQuantity += quantity;
        invested += Math.max(0, cost);
    }

    private void recordSell(int itemId, String itemName, int quantity, long revenue)
    {
        MutableItemStats item = item(itemId, itemName);
        item.soldQuantity += quantity;
        soldQuantity += quantity;
        grossRevenue += Math.max(0, revenue);

        int grossUnitPrice = quantity > 0 ? (int) Math.max(0, revenue / quantity) : 0;
        long tax = (long) calculateTaxPerItem(grossUnitPrice, itemId) * quantity;
        taxPaid += tax;

        Deque<InventoryLot> lots = inventory.get(itemId);
        int remaining = quantity;
        long cost = 0;
        while (remaining > 0 && lots != null && !lots.isEmpty())
        {
            InventoryLot lot = lots.peekFirst();
            int taken = Math.min(remaining, lot.quantity);
            long takenCost = lot.quantity == taken
                ? lot.cost
                : Math.round((double) lot.cost * taken / lot.quantity);
            cost += takenCost;
            lot.quantity -= taken;
            lot.cost -= takenCost;
            remaining -= taken;
            if (lot.quantity <= 0)
            {
                lots.removeFirst();
            }
        }

        int matched = quantity - remaining;
        if (matched <= 0)
        {
            return;
        }

        long matchedRevenue = Math.round((double) revenue * matched / quantity);
        long matchedTax = Math.round((double) tax * matched / quantity);
        long profit = matchedRevenue - matchedTax - cost;
        matchedQuantity += matched;
        matchedCost += cost;
        realizedProfit += profit;
        item.matchedQuantity += matched;
        item.profit += profit;
    }

    private MutableItemStats item(int itemId, String itemName)
    {
        MutableItemStats item = items.computeIfAbsent(itemId, ignored -> new MutableItemStats());
        if (item.itemName == null || item.itemName.isEmpty())
        {
            item.itemName = itemName == null || itemName.trim().isEmpty() ? "Item " + itemId : itemName.trim();
        }
        return item;
    }

    static int calculateTaxPerItem(int sellPrice, int itemId)
    {
        if (sellPrice < 50 || isTaxExempt(itemId))
        {
            return 0;
        }
        return Math.min(sellPrice / 50, 5_000_000);
    }

    /**
     * Tradeable item IDs; never infer an exemption from a display name.
     * Rules: Jagex Grand Exchange Tax & Item Sink (9 Dec 2021), and
     * Yama CAs & More! (29 May 2025). Item list cross-checked against the
     * OSRS Wiki Grand Exchange exemption list and RuneLite ItemID (1.12.37).
     * https://secure.runescape.com/m=news/grand-exchange-tax--item-sink?oldschool=1
     * https://secure.runescape.com/m=news/yama-cas--more?oldschool=1
     * https://oldschool.runescape.wiki/w/Grand_Exchange#Exempt_from_tax
     */
    private static boolean isTaxExempt(int itemId)
    {
        switch (itemId)
        {
            case 13190: // Old school bond
            case 233: // Pestle and mortar
            case 952: // Spade
            case 1733: // Needle
            case 1735: // Shears
            case 1755: // Chisel
            case 1785: // Glassblowing pipe
            case 2347: // Hammer
            case 5325: // Gardening trowel
            case 5329: // Secateurs
            case 5331: // Watering can (0)
            case 5341: // Rake
            case 5343: // Seed dibber
            case 8794: // Saw
            case 3008: // Energy potion (4)
            case 3010: // Energy potion (3)
            case 3012: // Energy potion (2)
            case 3014: // Energy potion (1)
            case 558: // Mind rune
            case 806: // Bronze dart
            case 807: // Iron dart
            case 808: // Steel dart
            case 882: // Bronze arrow
            case 884: // Iron arrow
            case 886: // Steel arrow
            case 315: // Shrimps
            case 329: // Salmon
            case 347: // Herring
            case 351: // Pike
            case 355: // Mackerel
            case 361: // Tuna
            case 365: // Bass
            case 379: // Lobster
            case 1891: // Cake
            case 2140: // Cooked chicken
            case 2142: // Cooked meat
            case 2309: // Bread
            case 2327: // Meat pie
            case 2552: // Ring of dueling (8)
            case 3853: // Games necklace (8)
            case 8007: // Varrock teleport
            case 8008: // Lumbridge teleport
            case 8009: // Falador teleport
            case 8010: // Camelot teleport
            case 8011: // Ardougne teleport
            case 8013: // Teleport to house
            case 28790: // Kourend castle teleport
            case 28824: // Civitas illa fortis teleport
                return true;
            default:
                return false;
        }
    }

    static long calculateProfitPerItem(int buyPrice, int sellPrice, int itemId)
    {
        if (buyPrice <= 0 || sellPrice <= 0)
        {
            return 0L;
        }
        return (long) sellPrice - buyPrice - calculateTaxPerItem(sellPrice, itemId);
    }

    static int calculateLowestBreakEvenSellPrice(int buyPrice, int itemId)
    {
        if (buyPrice <= 0)
        {
            return 0;
        }

        long low = buyPrice;
        long high = Math.min((long) Integer.MAX_VALUE, (long) buyPrice + 5_000_000L);
        if (high - calculateTaxPerItem((int) high, itemId) < buyPrice)
        {
            return 0;
        }

        while (low < high)
        {
            long middle = low + ((high - low) / 2L);
            long netSellPrice = middle - calculateTaxPerItem((int) middle, itemId);
            if (netSellPrice >= buyPrice)
            {
                high = middle;
            }
            else
            {
                low = middle + 1L;
            }
        }
        return (int) low;
    }

    private static final class InventoryLot
    {
        int quantity;
        long cost;

        InventoryLot(int quantity, long cost)
        {
            this.quantity = quantity;
            this.cost = cost;
        }
    }

    private static final class MutableItemStats
    {
        String itemName;
        int boughtQuantity;
        int soldQuantity;
        int matchedQuantity;
        long profit;
    }

    static final class SessionStatsSnapshot
    {
        final long startedAt;
        final long invested;
        final long grossRevenue;
        final long taxPaid;
        final long matchedCost;
        final long realizedProfit;
        final int boughtQuantity;
        final int soldQuantity;
        final int matchedQuantity;
        final int completedBuyOffers;
        final int completedSellOffers;
        final Map<Integer, SessionItemStats> items;

        SessionStatsSnapshot(
            long startedAt,
            long invested,
            long grossRevenue,
            long taxPaid,
            long matchedCost,
            long realizedProfit,
            int boughtQuantity,
            int soldQuantity,
            int matchedQuantity,
            int completedBuyOffers,
            int completedSellOffers,
            Map<Integer, SessionItemStats> items)
        {
            this.startedAt = startedAt;
            this.invested = invested;
            this.grossRevenue = grossRevenue;
            this.taxPaid = taxPaid;
            this.matchedCost = matchedCost;
            this.realizedProfit = realizedProfit;
            this.boughtQuantity = boughtQuantity;
            this.soldQuantity = soldQuantity;
            this.matchedQuantity = matchedQuantity;
            this.completedBuyOffers = completedBuyOffers;
            this.completedSellOffers = completedSellOffers;
            this.items = items;
        }

        double roi()
        {
            return matchedCost <= 0 ? 0d : (double) realizedProfit * 100d / matchedCost;
        }

        long hourlyProfit(long now)
        {
            long elapsed = Math.max(1, now - startedAt);
            return Math.round((double) realizedProfit * 3600d / elapsed);
        }
    }

    static final class SessionItemStats
    {
        final int itemId;
        final String itemName;
        final int boughtQuantity;
        final int soldQuantity;
        final int matchedQuantity;
        final long profit;

        SessionItemStats(
            int itemId,
            String itemName,
            int boughtQuantity,
            int soldQuantity,
            int matchedQuantity,
            long profit)
        {
            this.itemId = itemId;
            this.itemName = itemName;
            this.boughtQuantity = boughtQuantity;
            this.soldQuantity = soldQuantity;
            this.matchedQuantity = matchedQuantity;
            this.profit = profit;
        }
    }
}
