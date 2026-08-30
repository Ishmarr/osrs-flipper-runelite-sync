package com.osrsflipper.sync;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LastTradePriceBook
{
    private static final int MAX_ITEMS = 500;
    private static final long MAX_PRICE_TEST_SECONDS = 30;
    private static final int PRICE_TEST_FORMAT_VERSION = 1;
    private final Map<Integer, Entry> entries = new LinkedHashMap<>();
    private long localRevision;

    void clear()
    {
        entries.clear();
        localRevision++;
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
            if (quantity > 0 && existing != null &&
                (existing.pendingTestBuyPrice > 0 || existing.pendingTestBuyAt > 0))
            {
                clearPending(existing);
                markPendingChanged(existing);
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
            if (eventAt < Math.max(entry.clearedAt, entry.highestAuthoritativeClearAt))
            {
                return;
            }
            entry.pendingTestBuyPrice = unitPrice;
            entry.pendingTestBuyAt = Math.max(0, eventAt);
            markPendingChanged(entry);
        }
        else
        {
            long sellAt = Math.max(0, eventAt);
            long elapsed = sellAt - entry.pendingTestBuyAt;
            if (entry.pendingTestBuyAt > 0 &&
                elapsed >= 0 && elapsed <= MAX_PRICE_TEST_SECONDS)
            {
                entry.lastBuyPrice = entry.pendingTestBuyPrice;
                entry.lastSellPrice = unitPrice;
                entry.lastBuyAt = entry.pendingTestBuyAt;
                entry.lastSellAt = sellAt;
                entry.clearedAt = 0;
                markPublishedChanged(entry);
            }
            boolean pendingChanged = entry.pendingTestBuyPrice > 0 || entry.pendingTestBuyAt > 0;
            clearPending(entry);
            if (pendingChanged)
            {
                markPendingChanged(entry);
            }
        }
        trimOldest();
    }

    long revision()
    {
        return localRevision;
    }

    private void markPublishedChanged(Entry entry)
    {
        localRevision++;
        entry.modifiedRevision = localRevision;
        entry.publishedModifiedRevision = localRevision;
    }

    private void markPendingChanged(Entry entry)
    {
        localRevision++;
        entry.modifiedRevision = localRevision;
        entry.pendingModifiedRevision = localRevision;
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
                entry.highestAuthoritativeClearAt = Math.max(
                    Math.max(0, entry.highestAuthoritativeClearAt),
                    entry.clearedAt);
                entry.modifiedRevision = 0;
                entry.publishedModifiedRevision = 0;
                entry.pendingModifiedRevision = 0;
                if (entry.clearedAt > 0 && entry.clearedAt >= latestPriceTestAt(entry))
                {
                    clearPublished(entry);
                    // Een lokale buy die in exact dezelfde epochseconde als
                    // de tombstone begon, is de nieuwe generatie. Dit moet
                    // na een herstart hetzelfde blijven als tijdens runtime.
                    if (entry.pendingTestBuyAt < entry.clearedAt)
                    {
                        clearPending(entry);
                    }
                }
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

    Map<Integer, Long> mergeAuthoritative(Iterable<LastTradePriceView> serverEntries)
    {
        return mergeAuthoritative(serverEntries, Long.MAX_VALUE);
    }

    Map<Integer, Long> mergeAuthoritative(
        Iterable<LastTradePriceView> serverEntries,
        long requestLocalRevision)
    {
        Map<Integer, Long> advancedTombstones = new LinkedHashMap<>();
        if (serverEntries == null)
        {
            return advancedTombstones;
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
            boolean authoritativeTombstone = server.clearedAt > 0 &&
                server.lastBuyPrice <= 0 && server.lastSellPrice <= 0 &&
                server.clearedAt >= serverPriceAt;
            if (authoritativeTombstone)
            {
                boolean authoritativeClearAdvanced =
                    server.clearedAt > merged.highestAuthoritativeClearAt;
                if (!authoritativeClearAdvanced)
                {
                    // Een positieve generatie kan dezelfde serverseconde als
                    // haar voorganger-tombstone dragen. Onthoud daarom los van
                    // de zichtbare clear welke tombstones al verwerkt zijn;
                    // een stale cache mag die generatie niet opnieuw wissen.
                    continue;
                }
                long localPriceAt = latestPriceTestAt(merged);
                long localPendingAt = Math.max(0, merged.pendingTestBuyAt);
                boolean publishedChangedAfterRequest =
                    merged.publishedModifiedRevision > requestLocalRevision;
                boolean pendingChangedAfterRequest =
                    merged.pendingModifiedRevision > requestLocalRevision;
                boolean publishedClearAccepted = !publishedChangedAfterRequest &&
                    localPriceAt <= server.clearedAt;
                if (publishedClearAccepted)
                {
                    clearPublished(merged);
                }
                if (!pendingChangedAfterRequest &&
                    localPendingAt > 0 && localPendingAt < server.clearedAt)
                {
                    clearPending(merged);
                }
                boolean protectedPublishedWouldBeHidden = publishedChangedAfterRequest &&
                    localPriceAt > 0 && localPriceAt <= server.clearedAt;
                boolean protectedPendingWouldBeLostOnRestore = pendingChangedAfterRequest &&
                    localPendingAt > 0 && localPendingAt < server.clearedAt;
                if (!protectedPublishedWouldBeHidden && !protectedPendingWouldBeLostOnRestore)
                {
                    merged.clearedAt = Math.max(merged.clearedAt, server.clearedAt);
                }
                merged.highestAuthoritativeClearAt = server.clearedAt;
                entries.put(server.itemId, merged);
                // Rapporteer elke nieuw waargenomen tombstone ook wanneer een
                // nieuwere lokale prijsmeting terecht blijft bestaan. De
                // caller gebruikt clearedAt als bovengrens en kan daardoor
                // oude flipcycli opruimen zonder de nieuwe generatie te raken.
                advancedTombstones.put(server.itemId, server.clearedAt);
                continue;
            }
            long localAt = local == null ? 0 : Math.max(
                Math.max(latestPriceTestAt(local), local.pendingTestBuyAt),
                local.clearedAt);
            if (server.lastBuyPrice <= 0 || server.lastSellPrice <= 0 ||
                (server.clearedAt > 0 && server.clearedAt > serverPriceAt))
            {
                continue;
            }
            merged.highestAuthoritativeClearAt = Math.max(
                merged.highestAuthoritativeClearAt,
                server.clearedAt);
            // Een positieve response is een snapshot van het requestmoment.
            // Een lokale prijstest die daarna wijzigde wint daarom altijd,
            // ook wanneer beide generaties dezelfde epochseconde dragen.
            if (local != null && local.modifiedRevision > requestLocalRevision)
            {
                entries.put(server.itemId, merged);
                continue;
            }
            if (local != null && localAt > 0 && localAt > serverAt)
            {
                continue;
            }
            merged.lastBuyPrice = server.lastBuyPrice;
            merged.lastSellPrice = server.lastSellPrice;
            merged.lastBuyAt = server.lastBuyAt;
            merged.lastSellAt = server.lastSellAt;
            // Een positieve authoritatieve generatie vervangt de historische
            // tombstone, ook wanneer beide servermomenten in dezelfde seconde
            // vallen. De Worker gebruikt de hogere flip-id als tie-breaker.
            merged.clearedAt = 0;
            clearPending(merged);
            entries.put(server.itemId, merged);
        }
        trimOldest();
        return advancedTombstones;
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
                    Math.max(
                        entry.pendingTestBuyAt,
                        Math.max(entry.clearedAt, entry.highestAuthoritativeClearAt)))))
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
        long highestAuthoritativeClearAt;
        transient long modifiedRevision;
        transient long publishedModifiedRevision;
        transient long pendingModifiedRevision;
    }
}
