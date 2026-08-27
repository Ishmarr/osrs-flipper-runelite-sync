package com.osrsflipper.sync;

import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OfferStatusResolverTest
{
    @Test
    public void fullyFilledOfferStopsEvenBeforeRuneLiteChangesTheStateEnum()
    {
        assertEquals("completed", OsrsFlipperSyncPlugin.statusFor(
            GrandExchangeOfferState.BUYING, 640, 640));
        assertEquals("completed", OsrsFlipperSyncPlugin.statusFor(
            GrandExchangeOfferState.SELLING, 640, 640));
        assertEquals("partially_filled", OsrsFlipperSyncPlugin.statusFor(
            GrandExchangeOfferState.BUYING, 639, 640));
    }
}
