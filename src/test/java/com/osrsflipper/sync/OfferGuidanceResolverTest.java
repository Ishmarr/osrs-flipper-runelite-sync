package com.osrsflipper.sync;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OfferGuidanceResolverTest
{
    @Test
    public void crossSlotSellSelectsTheNewestExactUnboundBuy()
    {
        OfferGuidanceResolver.BuyCandidate oldSameSlot = buy(
            4, 21163, 50, 100, 150, "old-same-slot", 292_000, 300_000);
        OfferGuidanceResolver.BuyCandidate currentCrossSlot = buy(
            2, 21163, 50, 200, 250, "current-cross-slot", 292_362, 302_073);

        OfferGuidanceResolver.BuyCandidate selected = OfferGuidanceResolver.selectBuyForSell(
            4,
            21163,
            50,
            300,
            Arrays.asList(oldSameSlot, currentCrossSlot),
            Collections.emptySet());

        assertEquals("current-cross-slot", selected.offerId);
        OfferGuidanceResolver.Guidance guidance = OfferGuidanceResolver.linkedSell(
            299_000,
            301_000,
            295_000,
            selected);
        assertEquals(292_362, guidance.buyPrice);
        assertEquals(302_073, guidance.sellPrice);
        assertEquals("current-cross-slot", guidance.sourceBuyOfferId);
    }

    @Test
    public void alreadyLinkedWrongItemAndInsufficientBuysAreRejected()
    {
        OfferGuidanceResolver.BuyCandidate linked = buy(
            1, 21163, 50, 100, 250, "linked", 292_000, 300_000);
        OfferGuidanceResolver.BuyCandidate wrongItem = buy(
            2, 4151, 50, 110, 260, "wrong-item", 1_000, 1_100);
        OfferGuidanceResolver.BuyCandidate insufficient = buy(
            3, 21163, 49, 120, 270, "insufficient", 293_000, 301_000);
        HashSet<String> alreadyLinked = new HashSet<>();
        alreadyLinked.add("linked");

        assertNull(OfferGuidanceResolver.selectBuyForSell(
            4,
            21163,
            50,
            300,
            Arrays.asList(linked, wrongItem, insufficient),
            alreadyLinked));
    }

    @Test
    public void repricingUpdatesTheActualBuyButNeverLowersTheSellTarget()
    {
        OfferGuidanceResolver.Guidance buy = OfferGuidanceResolver.reprice(
            "buy",
            292_500,
            299_000,
            new OfferGuidanceResolver.Guidance(292_362, 302_073, ""));
        assertEquals(292_500, buy.buyPrice);
        assertEquals(302_073, buy.sellPrice);

        OfferGuidanceResolver.Guidance sell = OfferGuidanceResolver.reprice(
            "sell",
            300_000,
            301_000,
            new OfferGuidanceResolver.Guidance(292_500, 302_073, "buy-1"));
        assertEquals(292_500, sell.buyPrice);
        assertEquals(302_073, sell.sellPrice);
        assertEquals("buy-1", sell.sourceBuyOfferId);

        OfferGuidanceResolver.Guidance raised = OfferGuidanceResolver.reprice(
            "sell",
            304_000,
            303_000,
            sell);
        assertEquals(304_000, raised.sellPrice);
    }

    @Test
    public void buyPriceAndLowestPriceAreCapturedOnceAndSurviveBuyRepricing()
    {
        int frozenFloor = SessionStatsTracker.calculateLowestBreakEvenSellPrice(
            9_398,
            "Antidote++(4)");
        assertEquals(9_589, frozenFloor);

        OfferGuidanceResolver.Guidance repriced = OfferGuidanceResolver.buy(
            9_277,
            9_488,
            "Antidote++(4)",
            new OfferGuidanceResolver.Guidance(9_398, 9_681, "", frozenFloor),
            true);

        assertEquals(9_398, repriced.buyPrice);
        assertEquals(9_589, repriced.lowestSellPrice);
    }

    @Test
    public void directModifySuccessorPreservesTheOriginalLowestPrice()
    {
        assertTrue(OfferGuidanceResolver.continuesCancelledReprice(
            true,
            "cancelled",
            9_681,
            9_600,
            2_846,
            0,
            2_846));
        assertTrue(OfferGuidanceResolver.continuesCancelledReprice(
            true,
            "cancelled",
            9_681,
            9_488,
            2_846,
            1_634,
            1_212));
        assertTrue(OfferGuidanceResolver.continuesCancelledReprice(
            true,
            "partially_filled",
            9_681,
            9_488,
            2_846,
            1_634,
            1_212));
        assertFalse(OfferGuidanceResolver.continuesCancelledReprice(
            true,
            "partially_filled",
            9_681,
            9_488,
            2_846,
            1_634,
            2_846));

        OfferGuidanceResolver.Guidance modified = OfferGuidanceResolver.reprice(
            "sell",
            9_488,
            9_500,
            new OfferGuidanceResolver.Guidance(
                9_398,
                9_681,
                "buy-antidote",
                9_589));

        assertEquals(9_398, modified.buyPrice);
        assertEquals(9_589, modified.lowestSellPrice);
        assertEquals("buy-antidote", modified.sourceBuyOfferId);
    }

    @Test
    public void repeatedModifyOperationsKeepTheFirstFrozenLowestPrice()
    {
        OfferGuidanceResolver.Guidance original = new OfferGuidanceResolver.Guidance(
            9_398,
            9_681,
            "buy-antidote",
            9_589);
        OfferGuidanceResolver.Guidance firstModify = OfferGuidanceResolver.reprice(
            "sell",
            9_488,
            9_500,
            original);
        OfferGuidanceResolver.Guidance secondModify = OfferGuidanceResolver.reprice(
            "sell",
            9_750,
            9_800,
            firstModify);

        assertEquals(9_398, secondModify.buyPrice);
        assertEquals(9_800, secondModify.sellPrice);
        assertEquals(9_589, secondModify.lowestSellPrice);
        assertEquals("buy-antidote", secondModify.sourceBuyOfferId);
    }

    @Test
    public void completedEmptyOrUnrelatedOfferCannotInheritThePreviousFloor()
    {
        assertFalse(OfferGuidanceResolver.continuesCancelledReprice(
            true, "completed", 9_681, 9_488, 2_846, 1_634, 1_212));
        assertFalse(OfferGuidanceResolver.continuesCancelledReprice(
            false, "cancelled", 9_681, 9_488, 2_846, 1_634, 1_212));
        assertTrue(OfferGuidanceResolver.continuesCancelledReprice(
            true, "cancelled", 9_681, 9_681, 2_846, 1_634, 1_212));
        assertFalse(OfferGuidanceResolver.continuesCancelledReprice(
            true, "cancelled", 9_681, 9_488, 2_846, 1_634, 1_211));
    }

    @Test
    public void aTrulyNewBuyGetsItsOwnFloorInsteadOfTheTerminalPredecessorFloor()
    {
        OfferGuidanceResolver.Guidance next = OfferGuidanceResolver.buy(
            9_277,
            9_488,
            "Antidote++(4)",
            new OfferGuidanceResolver.Guidance(9_398, 9_681, "", 9_589),
            false);

        assertEquals(9_277, next.buyPrice);
        assertEquals(9_466, next.lowestSellPrice);
    }

    @Test
    public void linkedSellCarriesExactLowestPriceAcrossSlots()
    {
        OfferGuidanceResolver.BuyCandidate source = new OfferGuidanceResolver.BuyCandidate(
            1,
            5952,
            2_846,
            100,
            200,
            "buy-antidote",
            9_398,
            9_681,
            9_589);

        OfferGuidanceResolver.Guidance sell = OfferGuidanceResolver.linkedSell(
            9_488,
            9_700,
            9_277,
            source);
        OfferGuidanceResolver.Guidance raised = OfferGuidanceResolver.reprice(
            "sell",
            9_750,
            9_800,
            sell);

        assertEquals(9_589, sell.lowestSellPrice);
        assertEquals(9_589, raised.lowestSellPrice);
        assertEquals(9_800, raised.sellPrice);
    }

    @Test
    public void unlinkedSellDoesNotInventAFloorFromLiveWiki()
    {
        OfferGuidanceResolver.Guidance sell = OfferGuidanceResolver.linkedSell(
            9_488,
            9_700,
            9_277,
            null);

        assertEquals(0, sell.lowestSellPrice);
    }

    @Test
    public void legacySellBackfillsOnceFromItsFrozenSuggestedBuyPrice()
    {
        int backfilled = OfferGuidanceResolver.freezeLowestSellPrice(
            0,
            9_398,
            "Antidote++(4)");
        int unchanged = OfferGuidanceResolver.freezeLowestSellPrice(
            backfilled,
            9_277,
            "Antidote++(4)");

        assertEquals(9_589, backfilled);
        assertEquals(9_589, unchanged);
    }

    @Test
    public void terminalSellLifecycleMustRelinkToANewBuy()
    {
        assertFalse(OfferGuidanceResolver.continuesOfferLifecycle(true, "completed"));
        assertFalse(OfferGuidanceResolver.continuesOfferLifecycle(true, "cancelled"));
        assertTrue(OfferGuidanceResolver.continuesOfferLifecycle(true, "active"));
        assertFalse(OfferGuidanceResolver.continuesOfferLifecycle(false, "active"));

        OfferGuidanceResolver.Guidance terminalSell =
            new OfferGuidanceResolver.Guidance(9_398, 9_681, "old-buy", 9_589);
        OfferGuidanceResolver.BuyCandidate newBuy = new OfferGuidanceResolver.BuyCandidate(
            2,
            5952,
            2_846,
            300,
            400,
            "new-buy",
            9_277,
            9_488,
            9_466);

        boolean continuing = OfferGuidanceResolver.continuesOfferLifecycle(true, "completed");
        OfferGuidanceResolver.Guidance nextSell = continuing
            ? OfferGuidanceResolver.reprice("sell", 9_488, 9_500, terminalSell)
            : OfferGuidanceResolver.linkedSell(9_488, 9_500, 9_277, newBuy);

        assertEquals("new-buy", nextSell.sourceBuyOfferId);
        assertEquals(9_277, nextSell.buyPrice);
        assertEquals(9_466, nextSell.lowestSellPrice);
    }

    @Test
    public void legacyBuyCandidateBackfillsFloorFromFrozenBuyWithoutAffectingUnlinkedSell()
    {
        OfferGuidanceResolver.BuyCandidate legacyBuy = OfferGuidanceResolver.frozenBuyCandidate(
            1,
            5952,
            2_846,
            100,
            200,
            "legacy-buy",
            "Antidote++(4)",
            9_398,
            9_681,
            0);

        assertEquals(9_589, legacyBuy.lowestSellPrice);
        assertEquals(9_589,
            OfferGuidanceResolver.linkedSell(9_488, 9_700, 9_277, legacyBuy).lowestSellPrice);
        assertEquals(0,
            OfferGuidanceResolver.linkedSell(9_488, 9_700, 9_277, null).lowestSellPrice);
    }

    @Test
    public void positiveServerFloorWinsAcrossDevicesWhileLegacyZeroKeepsLocalFloor()
    {
        assertEquals(9_589,
            OfferGuidanceResolver.adoptServerLowestSellPrice(9_466, 9_589));
        assertEquals(9_589,
            OfferGuidanceResolver.adoptServerLowestSellPrice(9_589, 0));
    }

    private static OfferGuidanceResolver.BuyCandidate buy(
        int slot,
        int itemId,
        int filled,
        long startedAt,
        long lastEventAt,
        String offerId,
        int buyPrice,
        int sellPrice)
    {
        return new OfferGuidanceResolver.BuyCandidate(
            slot,
            itemId,
            filled,
            startedAt,
            lastEventAt,
            offerId,
            buyPrice,
            sellPrice);
    }
}
