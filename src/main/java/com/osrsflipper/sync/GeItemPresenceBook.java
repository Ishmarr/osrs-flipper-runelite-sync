package com.osrsflipper.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Remembers when an item with personal price guidance disappeared from every
 * local GE slot. This book never clears prices itself: the Worker owns the
 * account-wide decision and returns the authoritative tombstone.
 */
final class GeItemPresenceBook
{
    static final long ABSENCE_REFRESH_SECONDS = 5 * 60;
    static final long REFRESH_RETRY_SECONDS = 60;
    private static final int MAX_ITEMS = 500;

    private final Map<Integer, Entry> entries = new HashMap<>();

    void clear()
    {
        entries.clear();
    }

    void restore(Entry[] persisted)
    {
        entries.clear();
        if (persisted == null)
        {
            return;
        }
        for (Entry raw : persisted)
        {
            if (raw == null || raw.itemId <= 0)
            {
                continue;
            }
            Entry entry = new Entry();
            entry.itemId = raw.itemId;
            entry.present = raw.present;
            entry.absentSinceAt = Math.max(0, raw.absentSinceAt);
            entry.lastRefreshRequestedAt = Math.max(0, raw.lastRefreshRequestedAt);
            entry.lastTransitionAt = Math.max(0, raw.lastTransitionAt);
            entries.put(entry.itemId, entry);
        }
        trimOldest();
    }

    List<Entry> persistedEntries()
    {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.values())
        {
            result.add(entry.copy());
        }
        result.sort(Comparator.comparingInt(entry -> entry.itemId));
        return result;
    }

    boolean observe(long observedAt, Set<Integer> presentItemIds, Set<Integer> trackedItemIds)
    {
        long now = Math.max(0, observedAt);
        Set<Integer> present = positiveIds(presentItemIds);
        Set<Integer> tracked = positiveIds(trackedItemIds);
        boolean changed = false;

        Iterator<Map.Entry<Integer, Entry>> existing = entries.entrySet().iterator();
        while (existing.hasNext())
        {
            if (!tracked.contains(existing.next().getKey()))
            {
                existing.remove();
                changed = true;
            }
        }

        for (Integer itemId : tracked)
        {
            Entry entry = entries.get(itemId);
            boolean isPresent = present.contains(itemId);
            if (entry == null)
            {
                entry = new Entry();
                entry.itemId = itemId;
                entry.present = isPresent;
                entry.absentSinceAt = isPresent ? 0 : now;
                entry.lastTransitionAt = now;
                entries.put(itemId, entry);
                changed = true;
                continue;
            }

            boolean overdueUnconfirmed = entry.absentSinceAt > 0 &&
                now >= entry.absentSinceAt &&
                now - entry.absentSinceAt >= ABSENCE_REFRESH_SECONDS;
            if (isPresent)
            {
                if (overdueUnconfirmed)
                {
                    if (!entry.present)
                    {
                        entry.present = true;
                        entry.lastTransitionAt = now;
                        changed = true;
                    }
                    continue;
                }
                if (!entry.present || entry.absentSinceAt > 0 || entry.lastRefreshRequestedAt > 0)
                {
                    entry.present = true;
                    entry.absentSinceAt = 0;
                    entry.lastRefreshRequestedAt = 0;
                    entry.lastTransitionAt = now;
                    changed = true;
                }
                continue;
            }

            if (overdueUnconfirmed)
            {
                if (entry.present)
                {
                    entry.present = false;
                    entry.lastTransitionAt = now;
                    changed = true;
                }
                continue;
            }
            if (entry.present || entry.absentSinceAt <= 0)
            {
                entry.present = false;
                entry.absentSinceAt = now;
                entry.lastRefreshRequestedAt = 0;
                entry.lastTransitionAt = now;
                changed = true;
            }
        }

        trimOldest();
        return changed;
    }

    boolean markAuthoritativeRefreshDue(long observedAt)
    {
        long now = Math.max(0, observedAt);
        boolean due = false;
        for (Entry entry : entries.values())
        {
            if (entry.absentSinceAt <= 0 || now < entry.absentSinceAt ||
                now - entry.absentSinceAt < ABSENCE_REFRESH_SECONDS ||
                (entry.lastRefreshRequestedAt > 0 &&
                    now - entry.lastRefreshRequestedAt < REFRESH_RETRY_SECONDS))
            {
                continue;
            }
            entry.lastRefreshRequestedAt = now;
            due = true;
        }
        return due;
    }

    void forget(Collection<Integer> itemIds)
    {
        if (itemIds == null)
        {
            return;
        }
        for (Integer itemId : itemIds)
        {
            if (itemId != null)
            {
                entries.remove(itemId);
            }
        }
    }

    long absentSinceAt(int itemId)
    {
        Entry entry = entries.get(itemId);
        return entry == null ? 0 : entry.absentSinceAt;
    }

    long lastRefreshRequestedAt(int itemId)
    {
        Entry entry = entries.get(itemId);
        return entry == null ? 0 : entry.lastRefreshRequestedAt;
    }

    boolean isPresent(int itemId)
    {
        Entry entry = entries.get(itemId);
        return entry != null && entry.present;
    }

    private void trimOldest()
    {
        while (entries.size() > MAX_ITEMS)
        {
            Integer oldest = entries.values().stream()
                .min(Comparator.comparingLong(entry -> Math.max(
                    entry.lastTransitionAt,
                    Math.max(entry.absentSinceAt, entry.lastRefreshRequestedAt))))
                .map(entry -> entry.itemId)
                .orElse(null);
            if (oldest == null)
            {
                return;
            }
            entries.remove(oldest);
        }
    }

    private static Set<Integer> positiveIds(Set<Integer> itemIds)
    {
        Set<Integer> result = new HashSet<>();
        if (itemIds == null)
        {
            return result;
        }
        for (Integer itemId : itemIds)
        {
            if (itemId != null && itemId > 0)
            {
                result.add(itemId);
            }
        }
        return result;
    }

    static final class Entry
    {
        int itemId;
        boolean present;
        long absentSinceAt;
        long lastRefreshRequestedAt;
        long lastTransitionAt;

        Entry copy()
        {
            Entry copy = new Entry();
            copy.itemId = itemId;
            copy.present = present;
            copy.absentSinceAt = absentSinceAt;
            copy.lastRefreshRequestedAt = lastRefreshRequestedAt;
            copy.lastTransitionAt = lastTransitionAt;
            return copy;
        }
    }
}
