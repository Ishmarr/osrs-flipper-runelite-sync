package com.osrsflipper.sync;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StatsItemFilterTest
{
    @Test
    public void separatesAndSortsProfitAndLoss()
    {
        List<RuneliteOverviewView.PeriodItem> items = Arrays.asList(
            new RuneliteOverviewView.PeriodItem(1, "Kleine winst", 100, 1),
            new RuneliteOverviewView.PeriodItem(2, "Groot verlies", -900, 1),
            new RuneliteOverviewView.PeriodItem(3, "Grote winst", 500, 1),
            new RuneliteOverviewView.PeriodItem(4, "Klein verlies", -50, 1));

        assertEquals(3, OsrsFlipperSyncPanel.visibleStatsItems(items, false).get(0).itemId);
        assertEquals(2, OsrsFlipperSyncPanel.visibleStatsItems(items, true).get(0).itemId);
    }

    @Test
    public void totalSortsEveryNonZeroItemFromHighestProfitToLargestLoss()
    {
        List<RuneliteOverviewView.PeriodItem> items = Arrays.asList(
            new RuneliteOverviewView.PeriodItem(1, "Kleine winst", 100, 1),
            new RuneliteOverviewView.PeriodItem(2, "Groot verlies", -900, 1),
            new RuneliteOverviewView.PeriodItem(3, "Grote winst", 500, 1),
            new RuneliteOverviewView.PeriodItem(4, "Klein verlies", -50, 1),
            new RuneliteOverviewView.PeriodItem(5, "Geen resultaat", 0, 1));

        List<RuneliteOverviewView.PeriodItem> total = OsrsFlipperSyncPanel.visibleStatsItems(
            items,
            OsrsFlipperSyncPanel.StatsSortChoice.TOTAL);

        assertEquals(4, total.size());
        assertEquals(3, total.get(0).itemId);
        assertEquals(1, total.get(1).itemId);
        assertEquals(4, total.get(2).itemId);
        assertEquals(2, total.get(3).itemId);
    }

    @Test
    public void labelsMatchTheThreeUserFacingChoices()
    {
        assertEquals("Winst", OsrsFlipperSyncPanel.StatsSortChoice.PROFIT.toString());
        assertEquals("Verlies", OsrsFlipperSyncPanel.StatsSortChoice.LOSS.toString());
        assertEquals("Totaal", OsrsFlipperSyncPanel.StatsSortChoice.TOTAL.toString());
    }
}
