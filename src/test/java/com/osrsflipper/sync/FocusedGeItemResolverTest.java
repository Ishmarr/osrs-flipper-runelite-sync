package com.osrsflipper.sync;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
    public void hiddenWidgetsNeverLeakAStaleItem()
    {
        assertEquals(0, FocusedGeItemResolver.resolve(false, 2434, 11840, false, 4151, 1127));
    }
}
