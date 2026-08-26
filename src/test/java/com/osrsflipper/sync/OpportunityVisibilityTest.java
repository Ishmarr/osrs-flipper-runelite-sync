package com.osrsflipper.sync;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OpportunityVisibilityTest
{
    @Test
    public void hidesEveryItemThatAlreadyOccupiesAGeSlot()
    {
        RuneliteOverviewView.Opportunity first = opportunity(101);
        RuneliteOverviewView.Opportunity second = opportunity(202);
        FlipperOfferView activeBuy = new FlipperOfferView(
            1, 101, "Item 101", "buy", 100, 10, 0,
            "active", 1, 110, 111);

        List<RuneliteOverviewView.Opportunity> visible =
            OsrsFlipperSyncPanel.visibleHourlyOpportunities(
                Arrays.asList(first, second),
                Collections.singletonList(activeBuy));

        assertEquals(1, visible.size());
        assertEquals(202, visible.get(0).itemId);
    }

    private static RuneliteOverviewView.Opportunity opportunity(int itemId)
    {
        return new RuneliteOverviewView.Opportunity(
            itemId,
            "Item " + itemId,
            "hourly",
            100,
            110,
            111,
            99,
            0,
            0,
            10,
            5_000,
            1_000,
            1234);
    }
}
