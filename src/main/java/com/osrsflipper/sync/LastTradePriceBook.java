package com.osrsflipper.sync;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LastTradePriceBook
{
    private static final int MAX_ITEMS = 500;
    private final Map<Integer, Entry> entries = new LinkedHashMap<>();

    void clear()
    {
        entries.clear();
    }

    void recordTransition(
        int itemId,
        String side,
        int previousFilled,
        int previousSpent,
        int filled,
        int spent,
        int fallbackPrice,
        long eventAt)
    {
        int quantity = Math.max(0, filled - Math.max(0, previousFilled));
        if (itemId <= 0 || quantity <= 0 || (!"buy".equals(side) && !"sell".equals(side)))
        {
            return;
        }
        long amount = Math.max(0L, (long) spent - Math.max(0, previousSpent));
        int unitPrice = amount > 0
            ? (int) Math.min(Integer.MAX_VALUE, Math.round((double) amount / quantity))
            : Math.max(0, fallbackPrice);
        if (unitPrice <= 0)
        {
            return;
        }

        Entry entry = entries.computeIfAbsent(itemId, ignored -> new Entry());
        entry.itemId = itemId;
        if ("buy".equals(side))
        {
            entry.lastBuyPrice = unitPrice;
            entry.lastBuyAt = Math.max(0, eventAt);
        }
        else
        {
            entry.lastSellPrice = unitPrice;
            entry.lastSellAt = Math.max(0, eventAt);
        }
        trimOldest();
    }

    void restore(Entry[] stored)
    {
        entries.clear();
        if (stored == null)
        {
            return;
        }
        for (Entry entry : stored)
        {
            if (entry != null && entry.itemId > 0)
            {
                entry.lastBuyPrice = Math.max(0, entry.lastBuyPrice);
                entry.lastSellPrice = Math.max(0, entry.lastSellPrice);
                entry.lastBuyAt = Math.max(0, entry.lastBuyAt);
                entry.lastSellAt = Math.max(0, entry.lastSellAt);
                entries.put(entry.itemId, entry);
            }
        }
        trimOldest();
    }

    Map<Integer, LastTradePriceView> snapshot()
    {
        Map<Integer, LastTradePriceView> result = new LinkedHashMap<>();
        for (Entry entry : entries.values())
        {
            result.put(entry.itemId, new LastTradePriceView(
                entry.itemId,
                entry.lastBuyPrice,
                entry.lastSellPrice,
                entry.lastBuyAt,
                entry.lastSellAt));
        }
        return result;
    }

    List<Entry> persistedEntries()
    {
        return new ArrayList<>(entries.values());
    }

    private void trimOldest()
    {
        while (entries.size() > MAX_ITEMS)
        {
            Integer oldest = entries.values().stream()
                .min(Comparator.comparingLong(entry -> Math.max(entry.lastBuyAt, entry.lastSellAt)))
                .map(entry -> entry.itemId)
                .orElse(null);
            if (oldest == null)
            {
                return;
            }
            entries.remove(oldest);
        }
    }

    static final class Entry
    {
        int itemId;
        int lastBuyPrice;
        int lastSellPrice;
        long lastBuyAt;
        long lastSellAt;
    }
}
