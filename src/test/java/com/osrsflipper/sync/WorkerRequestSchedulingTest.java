package com.osrsflipper.sync;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WorkerRequestSchedulingTest
{
    @Test
    public void durableGeWorkAlwaysHasHighestPriority()
    {
        assertEquals(
            WorkerRequestCoordinator.Kind.SNAPSHOT,
            next(true, true, true, true, true, true, true, true));
        assertEquals(
            WorkerRequestCoordinator.Kind.EVENT,
            next(false, true, true, true, true, true, true, true));
        assertEquals(
            WorkerRequestCoordinator.Kind.SNAPSHOT,
            next(false, false, true, true, true, true, true, true));
        assertEquals(
            WorkerRequestCoordinator.Kind.STATE,
            next(false, false, false, true, true, true, true, true));
    }

    @Test
    public void lowerPriorityWorkHasADeterministicOrderAndCoalescesOutsideTheScheduler()
    {
        assertEquals(
            WorkerRequestCoordinator.Kind.CASH,
            next(false, false, false, false, true, true, true, true));
        assertEquals(
            WorkerRequestCoordinator.Kind.STATUS,
            next(false, false, false, false, false, true, true, true));
        assertEquals(
            WorkerRequestCoordinator.Kind.OVERVIEW,
            next(false, false, false, false, false, false, true, true));
        assertEquals(
            WorkerRequestCoordinator.Kind.HEARTBEAT,
            next(false, false, false, false, false, false, false, true));
        assertNull(next(false, false, false, false, false, false, false, false));
    }

    @Test
    public void unrelatedSuccessCannotEraseAnUnexpiredWorkerBackoff()
    {
        assertEquals(200, OsrsFlipperSyncPlugin.workerBackoffAfterSuccess(200, 150));
        assertEquals(0, OsrsFlipperSyncPlugin.workerBackoffAfterSuccess(200, 200));
        assertEquals(0, OsrsFlipperSyncPlugin.workerBackoffAfterSuccess(0, 150));
    }

    @Test
    public void temporaryCashFailuresRemainRetryableButPermanentClientErrorsDoNot()
    {
        assertTrue(OsrsFlipperSyncPlugin.retryableWorkerHttpStatus(408));
        assertTrue(OsrsFlipperSyncPlugin.retryableWorkerHttpStatus(425));
        assertTrue(OsrsFlipperSyncPlugin.retryableWorkerHttpStatus(429));
        assertTrue(OsrsFlipperSyncPlugin.retryableWorkerHttpStatus(500));
        assertTrue(OsrsFlipperSyncPlugin.retryableWorkerHttpStatus(503));
        assertFalse(OsrsFlipperSyncPlugin.retryableWorkerHttpStatus(400));
        assertFalse(OsrsFlipperSyncPlugin.retryableWorkerHttpStatus(404));
    }

    @Test
    public void geDeliveryPreemptsAndCoalescesAReadOnlyOverviewWithoutHealthFailure()
        throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        WorkerRequestCoordinator coordinator = coordinator(plugin);
        AtomicInteger cancellations = new AtomicInteger();
        Object overviewCall = new Object();
        assertTrue(coordinator.begin(
            WorkerRequestCoordinator.Kind.OVERVIEW,
            overviewCall,
            cancellations::incrementAndGet));
        setBoolean(plugin, "overviewInFlight", true);
        setBoolean(plugin, "overviewInFlightFreshMarket", true);
        setBoolean(plugin, "overviewInFlightFreshBuyLimits", true);

        Method preempt = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "preemptOverviewForGeDelivery");
        preempt.setAccessible(true);
        preempt.invoke(plugin);
        preempt.invoke(plugin);

        assertEquals(1, cancellations.get());
        assertTrue(booleanField(plugin, "overviewRefreshPending"));
        assertTrue(booleanField(plugin, "overviewFreshMarketPending"));
        assertTrue(booleanField(plugin, "overviewFreshBuyLimitsPending"));
        assertEquals("", health(plugin).banner(0));
        WorkerRequestCoordinator.Completion completion = coordinator.complete(
            WorkerRequestCoordinator.Kind.OVERVIEW, overviewCall);
        assertEquals(
            WorkerRequestCoordinator.CompletionStatus.LOCALLY_CANCELLED,
            completion.status);
        assertFalse(completion.shouldHandleResponse());
    }

    private static WorkerRequestCoordinator.Kind next(
        boolean snapshotContinuation,
        boolean event,
        boolean snapshot,
        boolean state,
        boolean cash,
        boolean status,
        boolean overview,
        boolean heartbeat)
    {
        return OsrsFlipperSyncPlugin.nextWorkerRequestKind(
            snapshotContinuation, event, snapshot, state, cash, status, overview, heartbeat);
    }

    private static WorkerRequestCoordinator coordinator(OsrsFlipperSyncPlugin plugin)
        throws Exception
    {
        Field field = OsrsFlipperSyncPlugin.class.getDeclaredField("workerRequests");
        field.setAccessible(true);
        return (WorkerRequestCoordinator) field.get(plugin);
    }

    private static SyncHealthTracker health(OsrsFlipperSyncPlugin plugin) throws Exception
    {
        Field field = OsrsFlipperSyncPlugin.class.getDeclaredField("syncHealth");
        field.setAccessible(true);
        return (SyncHealthTracker) field.get(plugin);
    }

    private static boolean booleanField(Object target, String name) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void setBoolean(Object target, String name, boolean value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }
}
