package com.osrsflipper.sync;

import com.google.gson.Gson;
import java.util.Collections;
import java.util.HashSet;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GeItemPresenceBookTest
{
    private static final int ITEM_ID = 4151;

    @Test
    public void requestsTheWorkerAtExactlyThreeHundredSecondsWithoutClearingLocally()
    {
        GeItemPresenceBook presence = new GeItemPresenceBook();
        LastTradePriceBook prices = new LastTradePriceBook();
        prices.mergeAuthoritative(Collections.singletonList(
            new LastTradePriceView(ITEM_ID, 2_000, 1_900, 90, 91)));

        assertTrue(presence.observe(
            100,
            Collections.emptySet(),
            Collections.singleton(ITEM_ID)));
        assertFalse(presence.markAuthoritativeRefreshDue(399));
        assertTrue(presence.markAuthoritativeRefreshDue(400));

        assertEquals(2_000, prices.snapshot().get(ITEM_ID).lastBuyPrice);
        assertEquals(1_900, prices.snapshot().get(ITEM_ID).lastSellPrice);
        assertEquals(400, presence.lastRefreshRequestedAt(ITEM_ID));
    }

    @Test
    public void duplicateEmptyObservationsAndShortSlotMovesKeepTheOriginalDeadline()
    {
        GeItemPresenceBook presence = new GeItemPresenceBook();
        HashSet<Integer> tracked = new HashSet<>(Collections.singleton(ITEM_ID));

        presence.observe(100, Collections.singleton(ITEM_ID), tracked);
        presence.observe(150, Collections.singleton(ITEM_ID), tracked);
        assertTrue(presence.isPresent(ITEM_ID));

        presence.observe(200, Collections.emptySet(), tracked);
        presence.observe(250, Collections.emptySet(), tracked);
        presence.observe(300, Collections.emptySet(), tracked);

        assertEquals(200, presence.absentSinceAt(ITEM_ID));
        assertFalse(presence.markAuthoritativeRefreshDue(499));
        assertTrue(presence.markAuthoritativeRefreshDue(500));
    }

    @Test
    public void anotherSameItemSlotKeepsTheItemPresentUntilTheLastCopyDisappears()
    {
        GeItemPresenceBook presence = new GeItemPresenceBook();
        HashSet<Integer> tracked = new HashSet<>(Collections.singleton(ITEM_ID));

        // De invoer is per item gededupliceerd: zolang slot 1 of slot 8 het
        // item nog bevat, blijft ITEM_ID in de aanwezigheidsset staan.
        presence.observe(100, Collections.singleton(ITEM_ID), tracked);
        presence.observe(200, Collections.singleton(ITEM_ID), tracked);
        assertEquals(0, presence.absentSinceAt(ITEM_ID));

        presence.observe(300, Collections.emptySet(), tracked);
        assertEquals(300, presence.absentSinceAt(ITEM_ID));
    }

    @Test
    public void reappearanceBeforeTheDeadlineStartsANewContinuousGap()
    {
        GeItemPresenceBook presence = new GeItemPresenceBook();
        HashSet<Integer> tracked = new HashSet<>(Collections.singleton(ITEM_ID));

        presence.observe(100, Collections.emptySet(), tracked);
        presence.observe(350, Collections.singleton(ITEM_ID), tracked);

        assertTrue(presence.isPresent(ITEM_ID));
        assertEquals(0, presence.absentSinceAt(ITEM_ID));
        assertEquals(0, presence.lastRefreshRequestedAt(ITEM_ID));

        presence.observe(500, Collections.emptySet(), tracked);
        assertFalse(presence.markAuthoritativeRefreshDue(799));
        assertTrue(presence.markAuthoritativeRefreshDue(800));
    }

    @Test
    public void overdueReappearanceKeepsRetryingUntilTheWorkerConfirmsTheClear()
    {
        GeItemPresenceBook presence = new GeItemPresenceBook();
        HashSet<Integer> tracked = new HashSet<>(Collections.singleton(ITEM_ID));

        presence.observe(100, Collections.emptySet(), tracked);
        assertTrue(presence.markAuthoritativeRefreshDue(400));
        presence.observe(450, Collections.singleton(ITEM_ID), tracked);

        assertTrue(presence.isPresent(ITEM_ID));
        assertEquals(100, presence.absentSinceAt(ITEM_ID));
        assertFalse(presence.markAuthoritativeRefreshDue(459));
        assertTrue(presence.markAuthoritativeRefreshDue(460));

        presence.forget(Collections.singleton(ITEM_ID));
        assertEquals(0, presence.absentSinceAt(ITEM_ID));
    }

    @Test
    public void disappearanceAfterAnOverdueReappearanceCannotRestartTheDeadline()
    {
        GeItemPresenceBook presence = new GeItemPresenceBook();
        HashSet<Integer> tracked = new HashSet<>(Collections.singleton(ITEM_ID));

        presence.observe(100, Collections.emptySet(), tracked);
        assertTrue(presence.markAuthoritativeRefreshDue(400));
        presence.observe(450, Collections.singleton(ITEM_ID), tracked);
        presence.observe(451, Collections.emptySet(), tracked);

        assertFalse(presence.isPresent(ITEM_ID));
        assertEquals(100, presence.absentSinceAt(ITEM_ID));
        assertFalse(presence.markAuthoritativeRefreshDue(459));
        assertTrue(presence.markAuthoritativeRefreshDue(460));
    }

    @Test
    public void restartPreservesTheOriginalAbsentSinceAndRetryCadence()
    {
        GeItemPresenceBook original = new GeItemPresenceBook();
        HashSet<Integer> tracked = new HashSet<>(Collections.singleton(ITEM_ID));
        original.observe(100, Collections.emptySet(), tracked);
        assertTrue(original.markAuthoritativeRefreshDue(400));

        Gson gson = new Gson();
        GeItemPresenceBook restored = new GeItemPresenceBook();
        restored.restore(gson.fromJson(
            gson.toJson(original.persistedEntries()),
            GeItemPresenceBook.Entry[].class));
        restored.observe(450, Collections.emptySet(), tracked);

        assertEquals(100, restored.absentSinceAt(ITEM_ID));
        assertFalse(restored.markAuthoritativeRefreshDue(459));
        assertTrue(restored.markAuthoritativeRefreshDue(460));
    }

    @Test
    public void completedAndCancelledOffersStillCountAsPresent()
    {
        assertTrue(OsrsFlipperSyncPlugin.isOccupiedGeOffer(
            ITEM_ID, GrandExchangeOfferState.BOUGHT));
        assertTrue(OsrsFlipperSyncPlugin.isOccupiedGeOffer(
            ITEM_ID, GrandExchangeOfferState.SOLD));
        assertTrue(OsrsFlipperSyncPlugin.isOccupiedGeOffer(
            ITEM_ID, GrandExchangeOfferState.CANCELLED_BUY));
        assertTrue(OsrsFlipperSyncPlugin.isOccupiedGeOffer(
            ITEM_ID, GrandExchangeOfferState.CANCELLED_SELL));
        assertFalse(OsrsFlipperSyncPlugin.isOccupiedGeOffer(
            ITEM_ID, GrandExchangeOfferState.EMPTY));
        assertFalse(OsrsFlipperSyncPlugin.isOccupiedGeOffer(
            0, GrandExchangeOfferState.BUYING));
    }

    @Test
    public void wikiBasedOpenCycleIsTrackedWithoutAPersonalPriceTest()
    {
        assertTrue(OsrsFlipperSyncPlugin.trackedGuidanceItemIds(
            Collections.emptyMap(),
            Collections.singleton(3004)).contains(3004));
    }
}
