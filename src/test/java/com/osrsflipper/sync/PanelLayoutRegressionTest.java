package com.osrsflipper.sync;

import java.awt.Component;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void slotTimerUsesTheLargerReadableLayout() throws Exception
    {
        Field timerSize = OsrsFlipperSyncPanel.class.getDeclaredField("SLOT_TIMER_FONT_SIZE");
        Field rowHeight = OsrsFlipperSyncPanel.class.getDeclaredField("SLOT_TIMER_ROW_HEIGHT");
        timerSize.setAccessible(true);
        rowHeight.setAccessible(true);

        assertEquals(14f, timerSize.getFloat(null), 0.001f);
        assertEquals(29, rowHeight.getInt(null));
    }

    @Test
    public void activeBuyOrSellPriceIsLargerAndBold() throws Exception
    {
        Method factory = OsrsFlipperSyncPanel.class.getDeclaredMethod(
            "coloredMetric", String.class, String.class, java.awt.Color.class, boolean.class);
        factory.setAccessible(true);
        JPanel row = (JPanel) factory.invoke(null, "Koop", "19 647 GP", java.awt.Color.WHITE, true);
        BorderLayout layout = (BorderLayout) row.getLayout();
        JLabel label = (JLabel) layout.getLayoutComponent(BorderLayout.WEST);
        JLabel value = (JLabel) layout.getLayoutComponent(BorderLayout.EAST);

        assertEquals(17f, label.getFont().getSize2D(), 0.001f);
        assertEquals(17f, value.getFont().getSize2D(), 0.001f);
        assertTrue(label.getFont().isBold());
        assertTrue(value.getFont().isBold());
        assertEquals(31, row.getPreferredSize().height);
    }

    @Test
    public void cashstackSaveButtonIsAReadablePrimaryAction() throws Exception
    {
        Method factory = OsrsFlipperSyncPanel.class.getDeclaredMethod(
            "primaryActionButton", String.class);
        factory.setAccessible(true);
        JButton button = (JButton) factory.invoke(null, "Cashstack opslaan");

        assertTrue(button.getFont().isBold());
        assertEquals(15f, button.getFont().getSize2D(), 0.001f);
        assertTrue(button.isOpaque());
        assertTrue(button.isContentAreaFilled());
        assertEquals(Integer.MAX_VALUE, button.getMaximumSize().width);
        assertEquals(40, button.getPreferredSize().height);
    }
}
