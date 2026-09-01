package com.osrsflipper.sync;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GeSlotTimerLifecycleTest
{
    @Test
    public void partialBuyFillResetsActiveTimer()
    {
        GeSlotTimerLifecycle.State state = GeSlotTimerLifecycle.advance(
            1_000, 0, 1_000, 0, 0, 25, true, false, 1_000, 1_300);

        assertEquals(1_300, state.timerStartedAt);
        assertEquals(1_300, state.lastFillAt);
        assertEquals(25, state.fillHighWaterMark);
    }

    @Test
    public void partialSellFillResetsActiveTimerAgain()
    {
        GeSlotTimerLifecycle.State state = GeSlotTimerLifecycle.advance(
            1_300, 1_300, 1_000, 25, 25, 40, true, false, 1_000, 1_500);

        assertEquals(1_500, state.timerStartedAt);
        assertEquals(1_500, state.lastFillAt);
    }

    @Test
    public void identicalEventAndGuidanceOnlyUpdatePreserveTimer()
    {
        GeSlotTimerLifecycle.State state = GeSlotTimerLifecycle.advance(
            1_300, 1_300, 1_000, 25, 25, 25, true, false, 1_000, 1_600);

        assertEquals(1_300, state.timerStartedAt);
        assertEquals(1_300, state.lastFillAt);
    }

    @Test
    public void repriceWithoutNewFillPreservesTimer()
    {
        GeSlotTimerLifecycle.State state = GeSlotTimerLifecycle.advance(
            1_300, 1_300, 1_000, 25, 25, 0, false, true, 1_600, 1_600);

        assertEquals(1_300, state.timerStartedAt);
        assertEquals(1_300, state.lastFillAt);
    }

    @Test
    public void fillInReplacementSegmentResetsTimerFromZeroBaseline()
    {
        GeSlotTimerLifecycle.State state = GeSlotTimerLifecycle.advance(
            1_300, 1_300, 1_000, 25, 25, 5, false, true, 1_600, 1_700);

        assertEquals(1_700, state.timerStartedAt);
        assertEquals(1_700, state.lastFillAt);
    }

    @Test
    public void newOfferFallsBackToItsStartedAt()
    {
        GeSlotTimerLifecycle.State state = GeSlotTimerLifecycle.advance(
            0, 0, 0, 0, 0, 0, false, false, 2_000, 2_000);

        assertEquals(2_000, state.timerStartedAt);
        assertEquals(0, state.lastFillAt);
    }

    @Test
    public void finalFillStillRecordsItsFillTimestamp()
    {
        GeSlotTimerLifecycle.State state = GeSlotTimerLifecycle.advance(
            1_500, 1_500, 1_000, 40, 40, 100, true, false, 1_000, 1_900);

        assertEquals(1_900, state.timerStartedAt);
        assertEquals(1_900, state.lastFillAt);
    }

    @Test
    public void regressionAndRecoveryToHighWaterDoNotCreateFalseFillReset()
    {
        GeSlotTimerLifecycle.State regressed = GeSlotTimerLifecycle.advance(
            1_300, 1_300, 1_000, 25, 25, 10, true, false, 1_000, 1_600);
        GeSlotTimerLifecycle.State recovered = GeSlotTimerLifecycle.advance(
            regressed.timerStartedAt,
            regressed.lastFillAt,
            1_000,
            regressed.fillHighWaterMark,
            10,
            25,
            true,
            false,
            1_000,
            1_700);

        assertEquals(1_300, regressed.timerStartedAt);
        assertEquals(25, regressed.fillHighWaterMark);
        assertEquals(1_300, recovered.timerStartedAt);
        assertEquals(25, recovered.fillHighWaterMark);
    }

    @Test
    public void usesWallClockObservationAsFillResetTimestamp()
    {
        long observedAt = 1_300;
        GeSlotTimerLifecycle.State state = GeSlotTimerLifecycle.advance(
            1_000, 0, 1_000, 0, 0, 1, true, false, 1_000, observedAt);

        assertEquals(observedAt, state.timerStartedAt);
        assertEquals(observedAt, state.lastFillAt);
    }
}
