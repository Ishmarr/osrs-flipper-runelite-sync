package com.osrsflipper.sync;

/**
 * Pure transition logic for the locally persisted GE slot timer.
 */
final class GeSlotTimerLifecycle
{
    private GeSlotTimerLifecycle()
    {
    }

    static State advance(
        long previousTimerStartedAt,
        long previousLastFillAt,
        long previousStartedAt,
        int previousFillHighWaterMark,
        int previousFilledQuantity,
        int nextFilledQuantity,
        boolean sameOffer,
        boolean continuingReplacement,
        long nextStartedAt,
        long eventAt)
    {
        long safeEventAt = Math.max(0, eventAt);
        long fallbackStartedAt = firstPositive(nextStartedAt, safeEventAt);
        boolean continuesPreviousTimer = sameOffer || continuingReplacement;

        long timerStartedAt = continuesPreviousTimer
            ? firstPositive(
                previousTimerStartedAt,
                previousLastFillAt,
                previousStartedAt,
                fallbackStartedAt)
            : fallbackStartedAt;
        long lastFillAt = continuesPreviousTimer ? Math.max(0, previousLastFillAt) : 0;

        int fillBaseline = sameOffer
            ? Math.max(Math.max(0, previousFillHighWaterMark), Math.max(0, previousFilledQuantity))
            : 0;
        boolean fillIncreased = sameOffer
            ? nextFilledQuantity > fillBaseline
            : nextFilledQuantity > 0;
        if (fillIncreased)
        {
            long fillAt = firstPositive(safeEventAt, fallbackStartedAt);
            timerStartedAt = fillAt;
            lastFillAt = fillAt;
        }

        int fillHighWaterMark = Math.max(fillBaseline, Math.max(0, nextFilledQuantity));
        return new State(timerStartedAt, lastFillAt, fillHighWaterMark);
    }

    private static long firstPositive(long... candidates)
    {
        for (long candidate : candidates)
        {
            if (candidate > 0)
            {
                return candidate;
            }
        }
        return 0;
    }

    static final class State
    {
        final long timerStartedAt;
        final long lastFillAt;
        final int fillHighWaterMark;

        private State(long timerStartedAt, long lastFillAt, int fillHighWaterMark)
        {
            this.timerStartedAt = timerStartedAt;
            this.lastFillAt = lastFillAt;
            this.fillHighWaterMark = fillHighWaterMark;
        }
    }
}
