package com.osrsflipper.sync;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
