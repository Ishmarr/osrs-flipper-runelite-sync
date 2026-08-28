package com.osrsflipper.sync;

import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class FocusedGeItemResolverTest
{
    @Test
    public void emptySetupKeepsTheNormalFlipListVisible()
    {
        assertEquals(0, FocusedGeItemResolver.resolve(true, -1, -1, false, 0, 4151));
        assertEquals(0, FocusedGeItemResolver.resolve(true, 0, 0, false, 0, 4151));
    }

    @Test
    public void selectedSetupItemActivatesFocusedMode()
    {
        assertEquals(2434, FocusedGeItemResolver.resolve(true, 2434, -1, false, 0, 0));
    }

    @Test
    public void selectedBuyItemFallsBackToTheCurrentSearchItem()
    {
        assertEquals(2434, FocusedGeItemResolver.resolve(true, 0, 2434, false, 0, 0));
    }

    @Test
    public void priceEditorIgnoresAStaleSearchItem()
    {
        assertEquals(2434, FocusedGeItemResolver.priceEditorItemId(2434, 1127, 4151));
        assertEquals(2434, FocusedGeItemResolver.priceEditorItemId(0, 2434, 4151));
        assertEquals(2434, FocusedGeItemResolver.priceEditorItemId(0, 0, 2434));
    }

    @Test
    public void visibleDetailsItemActivatesFocusedMode()
    {
        assertEquals(4151, FocusedGeItemResolver.resolve(false, 0, 0, true, 4151, 0));
        assertEquals(4151, FocusedGeItemResolver.resolve(true, 0, 0, true, 4151, 0));
    }

    @Test
    public void existingOfferUsesTheItemFromTheSelectedSlot()
    {
        assertEquals(4151, FocusedGeItemResolver.resolve(false, 0, 0, true, 0, 4151));
    }

    @Test
    public void selectedOfferTakesPriorityOverStaleDetailWidgets()
    {
        assertEquals(4151, FocusedGeItemResolver.resolve(false, 0, 0, true, 1127, 4151));
    }

    @Test
    public void selectedGeSlotIsConvertedFromOneBasedToZeroBased()
    {
        assertEquals(-1, FocusedGeItemResolver.selectedOfferIndex(0, 8));
        assertEquals(0, FocusedGeItemResolver.selectedOfferIndex(1, 8));
        assertEquals(7, FocusedGeItemResolver.selectedOfferIndex(8, 8));
        assertEquals(-1, FocusedGeItemResolver.selectedOfferIndex(9, 8));
    }

    @Test
    public void priceEditorUsesOnlyTheExactSelectedActiveSlot()
    {
        FlipperOfferView slotOne = offer(1, 21163, "buy", 292_000, 300_000);
        FlipperOfferView slotTwo = offer(2, 21163, "buy", 292_362, 302_073);

        FlipperOfferView selected = FocusedGeItemResolver.exactSelectedOffer(
            2,
            21163,
            GrandExchangeOfferState.BUYING,
            21163,
            "buy",
            java.util.Arrays.asList(slotOne, slotTwo));

        assertSame(slotTwo, selected);
        RuneliteOverviewView.Opportunity active = OsrsFlipperSyncPanel.activeOfferOpportunity(
            21163,
            "buy",
            java.util.Collections.singletonList(selected));
        MarketPriceView newerMarket = new MarketPriceView(
            21163, 310_000, 305_000, 10, 10, 10);
        assertEquals(292_362, FlipPriceResolver.editorPrice(
            "buy", active, newerMarket, null));
        assertEquals(302_073, FlipPriceResolver.editorPrice(
            "sell", active, newerMarket, null));
        assertNull(FocusedGeItemResolver.exactSelectedOffer(
            1, 4151, GrandExchangeOfferState.BUYING,
            21163, "buy", java.util.Arrays.asList(slotOne, slotTwo)));
        assertNull(FocusedGeItemResolver.exactSelectedOffer(
            1, 21163, GrandExchangeOfferState.SELLING,
            21163, "buy", java.util.Arrays.asList(slotOne, slotTwo)));
        assertNull(FocusedGeItemResolver.exactSelectedOffer(
            1, 21163, GrandExchangeOfferState.EMPTY,
            21163, "buy", java.util.Arrays.asList(slotOne, slotTwo)));
    }

    @Test
    public void hiddenWidgetsNeverLeakAStaleItem()
    {
        assertEquals(0, FocusedGeItemResolver.resolve(false, 2434, 11840, false, 4151, 1127));
    }

    @Test
    public void setupTitleDeterminesTheActivePriceSide()
    {
        assertEquals("buy", FocusedGeItemResolver.resolveSide(
            true, "<col=ff981f>Buy offer</col>", false, null));
        assertEquals("sell", FocusedGeItemResolver.resolveSide(
            true, "Grand Exchange: Set up Sell offer", false, null));
    }

    @Test
    public void existingOfferStateDeterminesTheActivePriceSide()
    {
        assertEquals("buy", FocusedGeItemResolver.resolveSide(
            false, "", true, GrandExchangeOfferState.BUYING));
        assertEquals("sell", FocusedGeItemResolver.resolveSide(
            false, "", true, GrandExchangeOfferState.SELLING));
        assertEquals("", FocusedGeItemResolver.resolveSide(
            false, "", true, GrandExchangeOfferState.EMPTY));
    }

    private static FlipperOfferView offer(
        int slot,
        int itemId,
        String side,
        int suggestedBuy,
        int suggestedSell)
    {
        return new FlipperOfferView(
            slot, itemId, "Test item", side, suggestedBuy,
            50, 10, "active", 1, 0,
            suggestedBuy, suggestedSell, 305_000, 290_000);
    }
}
