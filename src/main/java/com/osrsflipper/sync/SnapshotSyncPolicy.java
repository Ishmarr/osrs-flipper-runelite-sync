package com.osrsflipper.sync;

import java.util.Collection;

final class SnapshotSyncPolicy
{
    static final int LOCAL_RECONCILE_GAME_TICKS = 500;
    static final int HOURLY_SNAPSHOT_GAME_TICKS = 6000;

    enum TickAction
    {
        NONE,
        LOCAL_RECONCILE,
        HOURLY_SNAPSHOT
    }

    enum ReconcileMode
    {
        ALWAYS,
        WHEN_CHANGED,
        NEVER
    }

    private SnapshotSyncPolicy()
    {
    }

    static TickAction tickAction(int localTicks, int hourlyTicks)
    {
        if (hourlyTicks >= HOURLY_SNAPSHOT_GAME_TICKS)
        {
            return TickAction.HOURLY_SNAPSHOT;
        }
        if (localTicks >= LOCAL_RECONCILE_GAME_TICKS)
        {
            return TickAction.LOCAL_RECONCILE;
        }
        return TickAction.NONE;
    }

    static boolean shouldQueueSnapshot(ReconcileMode mode, boolean changed)
    {
        if (mode == ReconcileMode.ALWAYS)
        {
            return true;
        }
        if (mode == ReconcileMode.WHEN_CHANGED)
        {
            return changed;
        }
        return false;
    }

    static boolean isCompleteSlotSet(Collection<Integer> slotNumbers)
    {
        if (slotNumbers == null || slotNumbers.size() != 8)
        {
            return false;
        }

        boolean[] observed = new boolean[9];
        for (Integer slotNumber : slotNumbers)
        {
            if (slotNumber == null || slotNumber < 1 || slotNumber > 8 || observed[slotNumber])
            {
                return false;
            }
            observed[slotNumber] = true;
        }

        for (int slotNumber = 1; slotNumber <= 8; slotNumber++)
        {
            if (!observed[slotNumber])
            {
                return false;
            }
        }
        return true;
    }

    static boolean canUseSuccessfulSnapshotState(
        int status,
        boolean success,
        boolean reconcileRequired,
        Collection<Integer> slotNumbers)
    {
        return status >= 200 && status < 300 &&
            success &&
            !reconcileRequired &&
            isCompleteSlotSet(slotNumbers);
    }

    static boolean canFinishManualSync(
        boolean mismatch,
        boolean snapshotPending,
        boolean snapshotInFlight,
        boolean outboxEmpty)
    {
        return !mismatch && !snapshotPending && !snapshotInFlight && outboxEmpty;
    }
}
