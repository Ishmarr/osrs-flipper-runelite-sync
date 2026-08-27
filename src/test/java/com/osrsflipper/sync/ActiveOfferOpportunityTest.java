package com.osrsflipper.sync;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ActiveOfferOpportunityTest
{
    @Test
    public void focusedExistingOfferFallsBackToMatchingLocalSlot()
    {
        FlipperOfferView buy = offer(1, "buy", 641, 756, 640);
        FlipperOfferView sell = offer(2, "sell", 700, 757, 639);

        RuneliteOverviewView.Opportunity opportunity =
            OsrsFlipperSyncPanel.activeOfferOpportunity(
                21163, "sell", Arrays.asList(buy, sell));

        assertEquals("active_sell", opportunity.ranking);
        assertEquals(700, opportunity.sellPrice);
        assertEquals(757, opportunity.instantBuy);
        assertEquals(639, opportunity.instantSell);
    }

    @Test
    public void missingSlotDoesNotInventAnOpportunity()
    {
        assertNull(OsrsFlipperSyncPanel.activeOfferOpportunity(
            999, "buy", Arrays.asList(offer(1, "buy", 100, 110, 90))));
    }

    private static FlipperOfferView offer(
        int slot,
        String side,
        int price,
        int instantBuy,
        int instantSell)
    {
        return new FlipperOfferView(
            slot,
            21163,
            "Emerald amulet",
            side,
            price,
            3_805,
            0,
            "active",
            1,
            0,
            700,
            instantBuy,
            instantSell);
    }
}
