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

    @Test
    public void fullyFilledCancelledOneByOneOfferStillCountsAsCompleted()
    {
        assertEquals("completed", OsrsFlipperSyncPlugin.statusFor(
            GrandExchangeOfferState.CANCELLED_BUY, 1, 1));
        assertEquals("completed", OsrsFlipperSyncPlugin.statusFor(
            GrandExchangeOfferState.CANCELLED_SELL, 1, 1));
        assertEquals("cancelled", OsrsFlipperSyncPlugin.statusFor(
            GrandExchangeOfferState.CANCELLED_SELL, 0, 1));
    }

    @Test
    public void runtimeReconciliationRecoversOnlyKnownPositiveFillDeltas()
    {
        assertEquals(true, OsrsFlipperSyncPlugin.shouldRecordPriceTransition(
            false, false, 0, 1));
        assertEquals(true, OsrsFlipperSyncPlugin.shouldRecordPriceTransition(
            true, true, 0, 1));
        assertEquals(false, OsrsFlipperSyncPlugin.shouldRecordPriceTransition(
            true, false, 0, 1));
        assertEquals(false, OsrsFlipperSyncPlugin.shouldRecordPriceTransition(
            true, true, 1, 1));
    }
}
