package com.osrsflipper.sync;

import java.awt.Component;
import java.awt.Dimension;
import java.lang.reflect.Method;
import javax.swing.JLabel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PanelLayoutRegressionTest
{
    @Test
    public void emptyMessageKeepsFollowingCardsOnTheFullWidthAxis() throws Exception
    {
        Method factory = OsrsFlipperSyncPanel.class.getDeclaredMethod("emptyMessage", String.class);
        factory.setAccessible(true);
        JLabel message = (JLabel) factory.invoke(null, "Geen kansen");

        assertEquals(Component.LEFT_ALIGNMENT, message.getAlignmentX(), 0.001f);
        Dimension maximum = message.getMaximumSize();
        assertEquals(Integer.MAX_VALUE, maximum.width);
    }
}
