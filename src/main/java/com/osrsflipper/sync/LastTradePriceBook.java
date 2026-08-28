package com.osrsflipper.sync;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LastTradePriceBook
{
    private static final int MAX_ITEMS = 500;
    private static final long MAX_PRICE_TEST_SECONDS = 30;
    private static final long PUBLISHED_PRICE_TEST_TTL_SECONDS = 10 * 60;
    private static final int PRICE_TEST_FORMAT_VERSION = 1;
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
        int totalQuantity,
        String status,
        int fallbackPrice,
        long eventAt)
    {
        int quantity = Math.max(0, filled - Math.max(0, previousFilled));
        if (itemId <= 0 || (!"buy".equals(side) && !"sell".equals(side)))
        {
            return;
        }
        Entry existing = entries.get(itemId);
        boolean singleItemFill = quantity == 1 && totalQuantity == 1 && filled == 1 &&
            ("partially_filled".equals(status) || "completed".equals(status));
        if (!singleItemFill)
        {
            // Het actieve tegenoffer heeft nog geen fill en mag de zojuist
            // vastgelegde 1x1-aankoop niet wissen. Een echte andere fill wel.
            if (quantity > 0)
            {
                clearPending(existing);
            }
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
        entry.priceTestVersion = PRICE_TEST_FORMAT_VERSION;
        if ("buy".equals(side))
        {
            if (eventAt <= entry.clearedAt)
            {
                return;
            }
            entry.pendingTestBuyPrice = unitPrice;
            entry.pendingTestBuyAt = Math.max(0, eventAt);
        }
        else
        {
            long sellAt = Math.max(0, eventAt);
            long elapsed = sellAt - entry.pendingTestBuyAt;
            if (entry.pendingTestBuyPrice > unitPrice &&
                entry.pendingTestBuyAt > 0 &&
                elapsed >= 0 && elapsed <= MAX_PRICE_TEST_SECONDS)
            {
                entry.lastBuyPrice = entry.pendingTestBuyPrice;
                entry.lastSellPrice = unitPrice;
                entry.lastBuyAt = entry.pendingTestBuyAt;
                entry.lastSellAt = sellAt;
                entry.clearedAt = 0;
            }
            clearPending(entry);
        }
        trimOldest();
    }

    private static void clearPending(Entry entry)
    {
        if (entry == null)
        {
            return;
        }
        entry.pendingTestBuyPrice = 0;
        entry.pendingTestBuyAt = 0;
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
            if (entry != null && entry.itemId > 0 &&
                entry.priceTestVersion == PRICE_TEST_FORMAT_VERSION)
            {
                entry.lastBuyPrice = Math.max(0, entry.lastBuyPrice);
                entry.lastSellPrice = Math.max(0, entry.lastSellPrice);
                entry.lastBuyAt = Math.max(0, entry.lastBuyAt);
                entry.lastSellAt = Math.max(0, entry.lastSellAt);
                entry.pendingTestBuyPrice = Math.max(0, entry.pendingTestBuyPrice);
                entry.pendingTestBuyAt = Math.max(0, entry.pendingTestBuyAt);
                entry.clearedAt = Math.max(0, entry.clearedAt);
                entry.expiryRefreshForAt = Math.max(0, entry.expiryRefreshForAt);
                if (entry.clearedAt > 0 && entry.clearedAt >= latestPriceTestAt(entry))
                {
                    clearPublished(entry);
                    if (entry.pendingTestBuyAt <= entry.clearedAt)
                    {
                        clearPending(entry);
                    }
                }
                entries.put(entry.itemId, entry);
            }
        }
        trimOldest();
    }

    boolean markAuthoritativeExpiryRefreshDue(long now, Set<Integer> protectedItemIds)
    {
        boolean refreshDue = false;
        for (Entry entry : entries.values())
        {
            long publishedAt = latestPriceTestAt(entry);
            if (publishedAt <= 0 || entry.lastBuyPrice <= 0 || entry.lastSellPrice <= 0 ||
                (entry.clearedAt > 0 && entry.clearedAt >= publishedAt) ||
                (protectedItemIds != null && protectedItemIds.contains(entry.itemId)) ||
                now < publishedAt || now - publishedAt < PUBLISHED_PRICE_TEST_TTL_SECONDS ||
                // Dit onderdrukt alleen de extra controle op de 10-minutengrens.
                // Periodieke en GE-gestuurde overviews blijven gewoon doorlopen.
                entry.expiryRefreshForAt >= publishedAt)
            {
                continue;
            }
            entry.expiryRefreshForAt = publishedAt;
            refreshDue = true;
        }
        return refreshDue;
    }

    static boolean isOpenOffer(String side, int totalQuantity, String status)
    {
        return totalQuantity > 0 &&
            ("buy".equals(side) || "sell".equals(side)) &&
            ("active".equals(status) || "partially_filled".equals(status));
    }

    Map<Integer, LastTradePriceView> snapshot()
    {
        Map<Integer, LastTradePriceView> result = new LinkedHashMap<>();
        for (Entry entry : entries.values())
        {
            if (entry.clearedAt > 0 && entry.clearedAt >= latestPriceTestAt(entry))
            {
                continue;
            }
            result.put(entry.itemId, new LastTradePriceView(
                entry.itemId,
                entry.lastBuyPrice,
                entry.lastSellPrice,
                entry.lastBuyAt,
                entry.lastSellAt,
                entry.clearedAt));
        }
        return result;
    }

    void mergeAuthoritative(Iterable<LastTradePriceView> serverEntries)
    {
        if (serverEntries == null)
        {
            return;
        }
        for (LastTradePriceView server : serverEntries)
        {
            if (server == null || server.itemId <= 0)
            {
                continue;
            }
            Entry local = entries.get(server.itemId);
            long serverPriceAt = Math.max(server.lastBuyAt, server.lastSellAt);
            long serverAt = Math.max(serverPriceAt, server.clearedAt);
            Entry merged = local == null ? new Entry() : local;
            merged.itemId = server.itemId;
            merged.priceTestVersion = PRICE_TEST_FORMAT_VERSION;
            if (server.clearedAt > 0 && server.clearedAt >= serverPriceAt)
            {
                if (latestPriceTestAt(merged) <= server.clearedAt)
                {
                    clearPublished(merged);
                }
                if (merged.pendingTestBuyAt <= server.clearedAt)
                {
                    clearPending(merged);
                }
                merged.clearedAt = Math.max(merged.clearedAt, server.clearedAt);
                entries.put(server.itemId, merged);
                continue;
            }
            long localAt = local == null ? 0 : Math.max(
                Math.max(latestPriceTestAt(local), local.pendingTestBuyAt),
                local.clearedAt);
            if (local != null && localAt > serverAt)
            {
                continue;
            }
            if (server.lastBuyPrice <= 0 || server.lastSellPrice <= 0)
            {
                continue;
            }
            merged.lastBuyPrice = server.lastBuyPrice;
            merged.lastSellPrice = server.lastSellPrice;
            merged.lastBuyAt = server.lastBuyAt;
            merged.lastSellAt = server.lastSellAt;
            merged.clearedAt = server.clearedAt;
            clearPending(merged);
            entries.put(server.itemId, merged);
        }
        trimOldest();
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
                .min(Comparator.comparingLong(entry -> Math.max(
                    Math.max(entry.lastBuyAt, entry.lastSellAt),
                    Math.max(entry.pendingTestBuyAt, entry.clearedAt))))
                .map(entry -> entry.itemId)
                .orElse(null);
            if (oldest == null)
            {
                return;
            }
            entries.remove(oldest);
        }
    }

    private static long latestPriceTestAt(Entry entry)
    {
        return entry == null ? 0 : Math.max(entry.lastBuyAt, entry.lastSellAt);
    }

    private static void clearPublished(Entry entry)
    {
        if (entry == null)
        {
            return;
        }
        entry.lastBuyPrice = 0;
        entry.lastSellPrice = 0;
        entry.lastBuyAt = 0;
        entry.lastSellAt = 0;
    }

    static final class Entry
    {
        int itemId;
        int priceTestVersion;
        int lastBuyPrice;
        int lastSellPrice;
        long lastBuyAt;
        long lastSellAt;
        int pendingTestBuyPrice;
        long pendingTestBuyAt;
        long clearedAt;
        long expiryRefreshForAt;
    }
}
