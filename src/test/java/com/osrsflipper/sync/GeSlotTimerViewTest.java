package com.osrsflipper.sync;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class GeSlotTimerViewTest
{
    @Test
    public void formatsRunningTimerAsHoursMinutesAndSeconds()
    {
        GeSlotTimerView timer = GeSlotTimerView.create(" BUY ", 1_000, 0);

        assertNotNull(timer);
        assertEquals("buy", timer.getSide());
        assertEquals("Buy", timer.sideLabel());
        assertEquals("01:01:01", timer.timerText(4_661));
    }

    @Test
    public void freezesCompletedTimerAtEndedAt()
    {
        GeSlotTimerView timer = GeSlotTimerView.create("sell", 1_000, 4_661);

        assertNotNull(timer);
        assertEquals(1_000, timer.getStartedAt());
        assertEquals(4_661, timer.getEndedAt());
        assertEquals("01:01:01", timer.timerText(99_999));
    }

    @Test
    public void clampsFutureOrInvalidEndTimeToZeroOrCurrentTime()
    {
        GeSlotTimerView future = GeSlotTimerView.create("buy", 2_000, 0);
        GeSlotTimerView invalidEnd = GeSlotTimerView.create("sell", 1_000, 999);

        assertEquals("00:00:00", future.timerText(1_999));
        assertEquals("00:00:05", invalidEnd.timerText(1_005));
    }

    @Test
    public void keepsHoursBeyondTwoDigits()
    {
        GeSlotTimerView timer = GeSlotTimerView.create("sell", 1, 360_001);

        assertEquals("100:00:00", timer.timerText(999_999));
    }

    @Test
    public void rejectsUnknownSideAndMissingStartTime()
    {
        assertNull(GeSlotTimerView.create("trade", 1_000, 0));
        assertNull(GeSlotTimerView.create(null, 1_000, 0));
        assertNull(GeSlotTimerView.create("buy", 0, 0));
    }

    @Test
    public void reusesFormattedTextWithinOneSecondAndUpdatesOnNextSecond()
    {
        GeSlotTimerView timer = GeSlotTimerView.create("buy", 1_000, 0);
        GeSlotTimerView.TextCache cache = new GeSlotTimerView.TextCache();

        String first = cache.timerText(timer, 4_661);
        String sameSecond = cache.timerText(timer, 4_661);

        assertSame(first, sameSecond);
        assertEquals("01:01:01", first);
        assertEquals("01:01:02", cache.timerText(timer, 4_662));
    }
}
