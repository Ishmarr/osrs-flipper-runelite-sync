package com.osrsflipper.sync;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FocusedGeItemResolverTest
{
    @Test
    public void emptySetupKeepsTheNormalFlipListVisible()
    {
        assertEquals(0, FocusedGeItemResolver.resolve(true, -1, false, 0));
        assertEquals(0, FocusedGeItemResolver.resolve(true, 0, false, 0));
    }

    @Test
    public void selectedSetupItemActivatesFocusedMode()
    {
        assertEquals(2434, FocusedGeItemResolver.resolve(true, 2434, false, 0));
    }

    @Test
    public void visibleDetailsItemActivatesFocusedMode()
    {
        assertEquals(4151, FocusedGeItemResolver.resolve(false, 0, true, 4151));
        assertEquals(4151, FocusedGeItemResolver.resolve(true, 0, true, 4151));
    }

    @Test
    public void hiddenWidgetsNeverLeakAStaleItem()
    {
        assertEquals(0, FocusedGeItemResolver.resolve(false, 2434, false, 4151));
    }
}
