package com.osrsflipper.sync;

import java.util.Locale;

/**
 * Immutable, UI-only view of a single Grand Exchange slot timer.
 */
final class GeSlotTimerView
{
    private static final String BUY = "buy";
    private static final String SELL = "sell";

    private final String side;
    private final long startedAt;
    private final long endedAt;

    private GeSlotTimerView(String side, long startedAt, long endedAt)
    {
        this.side = side;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }

    /**
     * Builds a timer only for a known buy/sell side and a usable start time.
     * Returning {@code null} keeps corrupt or incomplete persisted state out of
     * the game UI without making overlay rendering fail.
     */
    static GeSlotTimerView create(String side, long startedAt, long endedAt)
    {
        String normalizedSide = normalizeSide(side);
        if (normalizedSide == null || startedAt <= 0)
        {
            return null;
        }

        return new GeSlotTimerView(normalizedSide, startedAt, Math.max(0, endedAt));
    }

    String getSide()
    {
        return side;
    }

    long getStartedAt()
    {
        return startedAt;
    }

    long getEndedAt()
    {
        return endedAt;
    }

    String sideLabel()
    {
        return BUY.equals(side) ? "Buy" : "Sell";
    }

    String timerText(long nowEpochSeconds)
    {
        long stopAt = endedAt >= startedAt ? endedAt : nowEpochSeconds;
        long elapsedSeconds = stopAt > startedAt ? stopAt - startedAt : 0;
        long hours = elapsedSeconds / 3600;
        long minutes = (elapsedSeconds % 3600) / 60;
        long seconds = elapsedSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private static String normalizeSide(String side)
    {
        if (side == null)
        {
            return null;
        }

        String normalized = side.trim().toLowerCase(Locale.ROOT);
        return BUY.equals(normalized) || SELL.equals(normalized) ? normalized : null;
    }

    /**
     * Per-slot formatter cache. A timer can only change once per epoch second;
     * state fields are part of the key so an offer transition in that same
     * second is still reflected immediately.
     */
    static final class TextCache
    {
        private String side;
        private long startedAt = Long.MIN_VALUE;
        private long endedAt = Long.MIN_VALUE;
        private long epochSecond = Long.MIN_VALUE;
        private String text;

        String timerText(GeSlotTimerView view, long nowEpochSeconds)
        {
            if (text != null &&
                epochSecond == nowEpochSeconds &&
                startedAt == view.startedAt &&
                endedAt == view.endedAt &&
                view.side.equals(side))
            {
                return text;
            }

            side = view.side;
            startedAt = view.startedAt;
            endedAt = view.endedAt;
            epochSecond = nowEpochSeconds;
            text = view.timerText(nowEpochSeconds);
            return text;
        }

        void clear()
        {
            side = null;
            startedAt = Long.MIN_VALUE;
            endedAt = Long.MIN_VALUE;
            epochSecond = Long.MIN_VALUE;
            text = null;
        }
    }
}
