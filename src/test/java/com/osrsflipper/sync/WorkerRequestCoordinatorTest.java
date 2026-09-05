package com.osrsflipper.sync;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WorkerRequestCoordinatorTest
{
    @Test
    public void onlyOneWorkerRequestCanBeActive()
    {
        WorkerRequestCoordinator coordinator = new WorkerRequestCoordinator();
        Object overview = new Object();
        Object event = new Object();

        assertTrue(coordinator.begin(
            WorkerRequestCoordinator.Kind.OVERVIEW, overview, () -> {}));
        assertFalse(coordinator.begin(
            WorkerRequestCoordinator.Kind.EVENT, event, () -> {}));
        assertEquals(WorkerRequestCoordinator.Kind.OVERVIEW, coordinator.activeKind());

        WorkerRequestCoordinator.Completion completion = coordinator.complete(
            WorkerRequestCoordinator.Kind.OVERVIEW, overview);
        assertTrue(completion.shouldHandleResponse());
        assertFalse(coordinator.isActive());
        assertTrue(coordinator.begin(
            WorkerRequestCoordinator.Kind.EVENT, event, () -> {}));
    }

    @Test
    public void overviewCanBePreemptedWithoutBecomingANetworkFailure()
    {
        WorkerRequestCoordinator coordinator = new WorkerRequestCoordinator();
        AtomicInteger cancellations = new AtomicInteger();
        Object overview = new Object();

        assertTrue(coordinator.begin(
            WorkerRequestCoordinator.Kind.OVERVIEW,
            overview,
            cancellations::incrementAndGet));
        assertTrue(coordinator.cancelOverview(
            WorkerRequestCoordinator.Cancellation.OVERVIEW_PREEMPTED));
        assertEquals(1, cancellations.get());
        assertTrue(coordinator.isActive());

        WorkerRequestCoordinator.Completion completion = coordinator.complete(
            WorkerRequestCoordinator.Kind.OVERVIEW, overview);
        assertEquals(
            WorkerRequestCoordinator.CompletionStatus.LOCALLY_CANCELLED,
            completion.status);
        assertEquals(
            WorkerRequestCoordinator.Cancellation.OVERVIEW_PREEMPTED,
            completion.cancellation);
        assertFalse(completion.shouldHandleResponse());
        assertFalse(coordinator.isActive());
    }

    @Test
    public void contextCancellationTargetsAnyWorkerCallAndStaleCallbacksCannotReleaseANewerCall()
    {
        WorkerRequestCoordinator coordinator = new WorkerRequestCoordinator();
        AtomicInteger cancellations = new AtomicInteger();
        Object oldCall = new Object();
        Object newCall = new Object();

        assertTrue(coordinator.begin(
            WorkerRequestCoordinator.Kind.EVENT,
            oldCall,
            cancellations::incrementAndGet));
        assertTrue(coordinator.cancelActive(
            WorkerRequestCoordinator.Cancellation.CONTEXT_CHANGED));
        assertEquals(1, cancellations.get());
        WorkerRequestCoordinator.Completion cancelled = coordinator.complete(
            WorkerRequestCoordinator.Kind.EVENT, oldCall);
        assertEquals(
            WorkerRequestCoordinator.CompletionStatus.LOCALLY_CANCELLED,
            cancelled.status);

        assertTrue(coordinator.begin(
            WorkerRequestCoordinator.Kind.STATUS, newCall, () -> {}));
        WorkerRequestCoordinator.Completion stale = coordinator.complete(
            WorkerRequestCoordinator.Kind.EVENT, oldCall);
        assertEquals(WorkerRequestCoordinator.CompletionStatus.STALE, stale.status);
        assertEquals(WorkerRequestCoordinator.Kind.STATUS, coordinator.activeKind());
    }

    @Test
    public void nonOverviewRequestsCannotBePreemptedAsOverview()
    {
        WorkerRequestCoordinator coordinator = new WorkerRequestCoordinator();
        Object event = new Object();
        assertTrue(coordinator.begin(
            WorkerRequestCoordinator.Kind.EVENT, event, () -> {}));
        assertFalse(coordinator.cancelOverview(
            WorkerRequestCoordinator.Cancellation.OVERVIEW_PREEMPTED));
        assertEquals(WorkerRequestCoordinator.Kind.EVENT, coordinator.activeKind());
    }
}
