package com.osrsflipper.sync;

import java.util.HashMap;
import java.util.Map;

/** Client-thread pacing for the public price source, independent of financial delivery. */
final class MarketPriceRefreshPolicy
{
    private final Map<Integer, Long> requestedAt = new HashMap<>();
    private final Map<Integer, Integer> failures = new HashMap<>();
    private final Map<Integer, Long> retryAt = new HashMap<>();
    private long sourceRetryAt;
    private long nextRequestAt;

    boolean due(int itemId, MarketPriceView cached, long now, long interval, boolean force)
    {
        return now >= sourceRetryAt && now >= retryAt.getOrDefault(itemId, 0L) &&
            now >= requestedAt.getOrDefault(itemId, 0L) + 1 &&
            (force || cached == null || now >= cached.fetchedAt + interval);
    }

    boolean sourceReady(long now) { return now >= Math.max(sourceRetryAt, nextRequestAt); }
    boolean itemReady(int itemId, long now) { return now >= retryAt.getOrDefault(itemId, 0L); }
    void started(int itemId, long now)
    {
        requestedAt.put(itemId, now);
        nextRequestAt = now + 1;
    }

    void succeeded(int itemId)
    {
        failures.remove(itemId);
        retryAt.remove(itemId);
    }

    void failed(int itemId, int status, long now, long retryAfter)
    {
        int count = Math.min(6, failures.getOrDefault(itemId, 0) + 1);
        failures.put(itemId, count);
        long delay = Math.max(Math.min(300, 15L << (count - 1)), Math.min(3600, Math.max(0, retryAfter)));
        retryAt.put(itemId, now + delay);
        if (status == 0 || status == 429 || status >= 500) sourceRetryAt = Math.max(sourceRetryAt, now + delay);
    }

    void clear()
    {
        requestedAt.clear();
        failures.clear();
        retryAt.clear();
        sourceRetryAt = 0;
        nextRequestAt = 0;
    }

    static long retryAfterSeconds(String header, long now)
    {
        if (header == null) return 0;
        try { return Math.max(0, Long.parseLong(header.trim())); }
        catch (NumberFormatException ignored)
        {
            try
            {
                return Math.max(0, java.time.ZonedDateTime.parse(header.trim(),
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() - now);
            }
            catch (java.time.format.DateTimeParseException invalid) { return 0; }
        }
    }
}
