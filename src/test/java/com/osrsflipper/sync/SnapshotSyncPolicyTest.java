package com.osrsflipper.sync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SnapshotSyncPolicyTest
{
    private static final Collection<Integer> COMPLETE_SLOTS =
        Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8);

    @Test
    public void cadenceConstantsRepresentFiveMinutesAndOneHour()
    {
        assertEquals(500, SnapshotSyncPolicy.LOCAL_RECONCILE_GAME_TICKS);
        assertEquals(6000, SnapshotSyncPolicy.HOURLY_SNAPSHOT_GAME_TICKS);
    }

    @Test
    public void tickActionHonoursBothBoundariesAndLetsHourlyWin()
    {
        Object[][] cases = {
            {-1, -1, SnapshotSyncPolicy.TickAction.NONE},
            {0, 0, SnapshotSyncPolicy.TickAction.NONE},
            {499, 5999, SnapshotSyncPolicy.TickAction.NONE},
            {500, 0, SnapshotSyncPolicy.TickAction.LOCAL_RECONCILE},
            {501, 5999, SnapshotSyncPolicy.TickAction.LOCAL_RECONCILE},
            {0, 6000, SnapshotSyncPolicy.TickAction.HOURLY_SNAPSHOT},
            {499, 6001, SnapshotSyncPolicy.TickAction.HOURLY_SNAPSHOT},
            {500, 6000, SnapshotSyncPolicy.TickAction.HOURLY_SNAPSHOT},
            {Integer.MAX_VALUE, Integer.MAX_VALUE, SnapshotSyncPolicy.TickAction.HOURLY_SNAPSHOT}
        };

        for (Object[] testCase : cases)
        {
            int localTicks = (Integer) testCase[0];
            int hourlyTicks = (Integer) testCase[1];
            SnapshotSyncPolicy.TickAction expected =
                (SnapshotSyncPolicy.TickAction) testCase[2];
            assertEquals(
                "Onverwachte actie voor local=" + localTicks + ", hourly=" + hourlyTicks,
                expected,
                SnapshotSyncPolicy.tickAction(localTicks, hourlyTicks));
        }
    }

    @Test
    public void everyReconcileModeHasAnExplicitChangedAndUnchangedContract()
    {
        EnumSet<SnapshotSyncPolicy.ReconcileMode> covered =
            EnumSet.noneOf(SnapshotSyncPolicy.ReconcileMode.class);

        for (SnapshotSyncPolicy.ReconcileMode mode : SnapshotSyncPolicy.ReconcileMode.values())
        {
            covered.add(mode);
            switch (mode)
            {
                case ALWAYS:
                    assertTrue(SnapshotSyncPolicy.shouldQueueSnapshot(mode, false));
                    assertTrue(SnapshotSyncPolicy.shouldQueueSnapshot(mode, true));
                    break;
                case WHEN_CHANGED:
                    assertFalse(SnapshotSyncPolicy.shouldQueueSnapshot(mode, false));
                    assertTrue(SnapshotSyncPolicy.shouldQueueSnapshot(mode, true));
                    break;
                case NEVER:
                    assertFalse(SnapshotSyncPolicy.shouldQueueSnapshot(mode, false));
                    assertFalse(SnapshotSyncPolicy.shouldQueueSnapshot(mode, true));
                    break;
                default:
                    throw new AssertionError("Ongeteste reconciliatiemodus: " + mode);
            }
        }

        assertEquals(EnumSet.allOf(SnapshotSyncPolicy.ReconcileMode.class), covered);
        assertFalse(SnapshotSyncPolicy.shouldQueueSnapshot(null, false));
        assertFalse(SnapshotSyncPolicy.shouldQueueSnapshot(null, true));
    }

    @Test
    public void completeSlotSetRequiresEverySlotExactlyOnce()
    {
        assertTrue(SnapshotSyncPolicy.isCompleteSlotSet(COMPLETE_SLOTS));
        assertTrue(SnapshotSyncPolicy.isCompleteSlotSet(
            Arrays.asList(8, 3, 1, 7, 5, 2, 6, 4)));

        assertFalse(SnapshotSyncPolicy.isCompleteSlotSet(null));
        assertFalse(SnapshotSyncPolicy.isCompleteSlotSet(Collections.emptyList()));
        assertFalse(SnapshotSyncPolicy.isCompleteSlotSet(Arrays.asList(1, 2, 3, 4, 5, 6, 7)));
        assertFalse(SnapshotSyncPolicy.isCompleteSlotSet(
            Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 8)));
        assertFalse(SnapshotSyncPolicy.isCompleteSlotSet(
            Arrays.asList(1, 2, 3, 4, 5, 6, 7, 7)));
        assertFalse(SnapshotSyncPolicy.isCompleteSlotSet(
            Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7)));
        assertFalse(SnapshotSyncPolicy.isCompleteSlotSet(
            Arrays.asList(2, 3, 4, 5, 6, 7, 8, 9)));
        assertFalse(SnapshotSyncPolicy.isCompleteSlotSet(
            Arrays.asList(1, 2, 3, 4, null, 6, 7, 8)));
    }

    @Test
    public void successfulSnapshotStateRequiresEveryResponseInvariant()
    {
        for (int status = 0; status <= 599; status++)
        {
            boolean expected = status >= 200 && status < 300;
            assertEquals(
                "Onverwachte statusclassificatie voor HTTP " + status,
                expected,
                SnapshotSyncPolicy.canUseSuccessfulSnapshotState(
                    status,
                    true,
                    false,
                    COMPLETE_SLOTS));
        }

        assertFalse(SnapshotSyncPolicy.canUseSuccessfulSnapshotState(
            200, false, false, COMPLETE_SLOTS));
        assertFalse(SnapshotSyncPolicy.canUseSuccessfulSnapshotState(
            200, true, true, COMPLETE_SLOTS));
        assertFalse(SnapshotSyncPolicy.canUseSuccessfulSnapshotState(
            200, true, false, Arrays.asList(1, 2, 3, 4, 5, 6, 7)));
        assertFalse(SnapshotSyncPolicy.canUseSuccessfulSnapshotState(
            200, true, false, Arrays.asList(1, 2, 3, 4, 5, 6, 7, 7)));
        assertFalse(SnapshotSyncPolicy.canUseSuccessfulSnapshotState(
            200, true, false, null));
    }

    @Test
    public void manualSyncOnlyFinishesInTheSingleSafeBooleanState()
    {
        int accepted = 0;
        for (boolean mismatch : new boolean[]{false, true})
        {
            for (boolean snapshotPending : new boolean[]{false, true})
            {
                for (boolean snapshotInFlight : new boolean[]{false, true})
                {
                    for (boolean outboxEmpty : new boolean[]{false, true})
                    {
                        boolean expected = !mismatch && !snapshotPending &&
                            !snapshotInFlight && outboxEmpty;
                        boolean actual = SnapshotSyncPolicy.canFinishManualSync(
                            mismatch,
                            snapshotPending,
                            snapshotInFlight,
                            outboxEmpty);
                        assertEquals(
                            "Onverwachte manual-syncbeslissing voor mismatch=" + mismatch +
                                ", pending=" + snapshotPending +
                                ", inFlight=" + snapshotInFlight +
                                ", outboxEmpty=" + outboxEmpty,
                            expected,
                            actual);
                        if (actual)
                        {
                            accepted++;
                        }
                    }
                }
            }
        }

        assertEquals(1, accepted);
    }

    @Test
    public void mutableCollectionsAreEvaluatedFromTheirCurrentExactContents()
    {
        Collection<Integer> slots = new ArrayList<>(COMPLETE_SLOTS);
        assertTrue(SnapshotSyncPolicy.isCompleteSlotSet(slots));
        slots.remove(Integer.valueOf(8));
        slots.add(7);
        assertFalse(SnapshotSyncPolicy.isCompleteSlotSet(slots));
    }
}
