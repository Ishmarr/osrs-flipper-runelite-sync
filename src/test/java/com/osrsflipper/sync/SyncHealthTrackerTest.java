package com.osrsflipper.sync;

import org.junit.Test;
import static org.junit.Assert.*;

public class SyncHealthTrackerTest
{
    @Test
    public void unrelatedSuccessCannotHideFailedDeliveryOrOverview()
    {
        SyncHealthTracker health = new SyncHealthTracker();
        health.succeed(SyncHealthTracker.Channel.OVERVIEW, 100);
        health.fail(SyncHealthTracker.Channel.OVERVIEW, "HTTP 503", 200);
        health.fail(SyncHealthTracker.Channel.EVENTS, "netwerkfout/time-out", 201);
        long retryAt = health.retryAt(SyncHealthTracker.Channel.OVERVIEW);
        health.succeed(SyncHealthTracker.Channel.HEARTBEAT, 202);
        assertTrue(health.failed(SyncHealthTracker.Channel.OVERVIEW));
        assertTrue(health.failed(SyncHealthTracker.Channel.EVENTS));
        assertEquals(retryAt, health.retryAt(SyncHealthTracker.Channel.OVERVIEW));
        assertTrue(health.banner(7).contains("GE-wachtrij: 7"));
        assertTrue(health.banner(7).contains("Laatst OK:"));
        health.succeed(SyncHealthTracker.Channel.OVERVIEW, 203);
        assertFalse(health.failed(SyncHealthTracker.Channel.OVERVIEW));
        assertTrue(health.failed(SyncHealthTracker.Channel.EVENTS));
        health.succeed(SyncHealthTracker.Channel.EVENTS, 204);
        assertEquals("", health.banner(0));
    }

    @Test
    public void backoffIsBoundedAndOnlyOwnRecoveryResetsIt()
    {
        SyncHealthTracker health = new SyncHealthTracker();
        long[] delays = {15, 30, 60, 120, 240, 300, 300, 300};
        for (long delay : delays)
        {
            health.fail(SyncHealthTracker.Channel.OVERVIEW, "time-out", 1000);
            assertEquals(1000 + delay, health.retryAt(SyncHealthTracker.Channel.OVERVIEW));
        }
        health.succeed(SyncHealthTracker.Channel.OVERVIEW, 1400);
        assertEquals(0, health.retryAt(SyncHealthTracker.Channel.OVERVIEW));
        health.fail(SyncHealthTracker.Channel.OVERVIEW, "time-out", 1500);
        assertEquals(1515, health.retryAt(SyncHealthTracker.Channel.OVERVIEW));
    }

    @Test
    public void accountSwitchClearsOldHealthAndInvalidPairingRequestsUserAction()
    {
        SyncHealthTracker health = new SyncHealthTracker();
        health.fail(SyncHealthTracker.Channel.CONNECTION, "ongeldig", 100);
        assertTrue(health.banner(0).contains("Opnieuw koppelen via Sync"));
        assertFalse(health.banner(0).contains("Automatisch herstel actief"));
        health.clear();
        assertEquals("", health.banner(0));
        assertEquals(0, health.retryAt(SyncHealthTracker.Channel.CONNECTION));
    }
}
