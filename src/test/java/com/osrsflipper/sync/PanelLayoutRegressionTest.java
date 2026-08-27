package com.osrsflipper.sync;

import java.awt.Component;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import javax.swing.JLabel;
import javax.swing.JPanel;
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

    @Test
    public void detailLabelsAndValuesUseTheReadableProfitFontSize() throws Exception
    {
        Method factory = OsrsFlipperSyncPanel.class.getDeclaredMethod(
            "compactMetric", String.class, String.class);
        factory.setAccessible(true);
        JPanel row = (JPanel) factory.invoke(null, "Aantal", "133 213");
        BorderLayout layout = (BorderLayout) row.getLayout();
        JLabel label = (JLabel) layout.getLayoutComponent(BorderLayout.WEST);
        JLabel value = (JLabel) layout.getLayoutComponent(BorderLayout.EAST);

        assertEquals(15f, label.getFont().getSize2D(), 0.001f);
        assertEquals(15f, value.getFont().getSize2D(), 0.001f);
    }

    @Test
    public void tabsAndWebsiteStatisticsUseReadableFontSizes() throws Exception
    {
        Field tabSize = OsrsFlipperSyncPanel.class.getDeclaredField("TAB_FONT_SIZE");
        Field detailSize = OsrsFlipperSyncPanel.class.getDeclaredField("DETAIL_FONT_SIZE");
        tabSize.setAccessible(true);
        detailSize.setAccessible(true);

        assertEquals(13f, tabSize.getFloat(null), 0.001f);
        assertEquals(15f, detailSize.getFloat(null), 0.001f);
    }
}
