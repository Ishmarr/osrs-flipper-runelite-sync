package com.osrsflipper.sync;

import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class PeriodStatsAttributionTest
{
    private static final String COMPLETE = "{\"realized_profit\":200,\"roi_percent\":4," +
        "\"profit_per_hour\":300,\"ge_tax\":10,\"trading_volume\":5000," +
        "\"completed_flips\":2,\"items\":[{\"item_id\":199,\"item_name\":\"Grimy item\"," +
        "\"realized_profit\":200,\"completed_flips\":2}]}";

    @Test
    public void absentAvailabilityFlagKeepsLegacyCompletePeriodsCompatible() throws Exception
    {
        Object response = parse(payload(COMPLETE));
        assertTrue(isComplete(response));
        RuneliteOverviewView view = toView(response);
        assertTrue(view.today.attributionComplete);
        assertEquals("", view.today.attributionError);
        assertEquals(200, view.today.realizedProfit);
        assertEquals(1, view.today.items.size());
        assertTrue(new RuneliteOverviewView.PeriodStats(1, 2, 3, 4, 5, 6).attributionComplete);
    }

    @Test
    public void explicitlyUnavailablePeriodAllowsMissingNumbersWithoutBlockingOtherOverviewData() throws Exception
    {
        Object response = parse(payload("{\"attribution_complete\":false}"));
        assertTrue(isComplete(response));
        RuneliteOverviewView view = toView(response);
        assertFalse(view.today.attributionComplete);
        assertTrue(view.today.attributionError.contains("Onvoldoende historische verkoopgegevens"));
        assertTrue(view.today.items.isEmpty());
        assertTrue(view.month.attributionComplete);
        assertTrue(view.total.attributionComplete);
        assertEquals(200, view.month.realizedProfit);
        assertEquals(200, view.total.realizedProfit);
        assertEquals(1000, view.cash.available);
        assertEquals(17, view.focus.effectiveMaximumQuantity());
    }

    @Test
    public void explicitFalseDiscardsPartialNumericTotalsAndItemsAndPreservesReason() throws Exception
    {
        String period = "{\"attribution_complete\":false," +
            "\"attribution_error\":\"Verkoopmoment ontbreekt\"," + COMPLETE.substring(1);
        RuneliteOverviewView view = toView(parse(payload(period)));
        assertFalse(view.today.attributionComplete);
        assertEquals("Verkoopmoment ontbreekt", view.today.attributionError);
        assertTrue(view.today.items.isEmpty());
    }

    @Test
    public void absentOrTrueAvailabilityDoesNotExcuseMalformedNumericStats() throws Exception
    {
        assertFalse(isComplete(parse(payload("{\"attribution_complete\":true}"))));
        assertFalse(isComplete(parse(payload("{}"))));
        assertFalse(isComplete(parse(payload(COMPLETE.replace("\"roi_percent\":4", "\"roi_percent\":null")))));
    }

    @Test
    public void unavailableSelectionClearsFakeValuesAndOldItemsAndPeriodSwitchRestoresThem() throws Exception
    {
        RuneliteOverviewView healthy = toView(parse(payload(COMPLETE)));
        RuneliteOverviewView unavailable = toView(parse(payload("{\"attribution_complete\":false," +
            "\"attribution_error\":\"Ontbrekende <historiek>\"}")));
        OsrsFlipperSyncPanel[] holder = new OsrsFlipperSyncPanel[1];
        onEdt(() -> {
            holder[0] = new OsrsFlipperSyncPanel(null, () -> {}, () -> {}, () -> {}, () -> {}, ignored -> {});
            holder[0].updateOverview(healthy);
        });
        try
        {
            onEdt(() -> {
                assertEquals("+200 GP", label(holder[0], "statsProfit").getText());
                assertTrue(text((Container) field(holder[0], "statsItemsList")).contains("Grimy item"));
                holder[0].updateOverview(unavailable);
            });
            onEdt(() -> {
                OsrsFlipperSyncPanel panel = holder[0];
                String cashBefore = label(panel, "cashAvailable").getText();
                assertUnavailable(panel);
                String reason = text((Container) field(panel, "statsItemsList"));
                assertTrue(reason.contains("Statistieken niet beschikbaar"));
                assertTrue(reason.contains("&lt;historiek&gt;"));
                assertFalse(reason.contains("Grimy item"));

                JComboBox<?> period = (JComboBox<?>) field(panel, "statsPeriod");
                for (int index : new int[] {1, 2})
                {
                    period.setSelectedIndex(index);
                    assertEquals("+200 GP", label(panel, "statsProfit").getText());
                    assertEquals("4,00%", label(panel, "statsRoi").getText());
                    assertEquals("2", label(panel, "statsFlips").getText());
                    assertTrue(text((Container) field(panel, "statsItemsList")).contains("Grimy item"));
                    assertFalse(text((Container) field(panel, "statsItemsList")).contains("Statistieken niet beschikbaar"));
                }
                period.setSelectedIndex(0);
                assertUnavailable(panel);
                assertEquals(cashBefore, label(panel, "cashAvailable").getText());
                panel.updateOverview(healthy);
            });
            onEdt(() -> {
                assertEquals("+200 GP", label(holder[0], "statsProfit").getText());
                assertTrue(text((Container) field(holder[0], "statsItemsList")).contains("Grimy item"));
            });
        }
        finally
        {
            onEdt(() -> holder[0].dispose());
        }
    }

    private static void assertUnavailable(OsrsFlipperSyncPanel panel) throws Exception
    {
        for (String name : new String[] {"statsProfit", "statsRoi", "statsHourly", "statsTax", "statsVolume", "statsFlips"})
        {
            assertEquals(name, "—", label(panel, name).getText());
        }
    }

    private static String payload(String today)
    {
        return "{\"success\":true,\"generated_at\":100,\"opportunities\":{\"hourly\":[]," +
            "\"focus\":{\"item_id\":199,\"maximum_quantity\":17}},\"stats\":{\"today\":" + today +
            ",\"month\":" + COMPLETE + ",\"total\":" + COMPLETE + "},\"price_tests\":[]," +
            "\"cash\":{\"available\":1000,\"reserved\":0,\"available_plus_reserved\":1000,\"updated_at\":100}}";
    }

    private static Object parse(String value) throws Exception
    {
        return new Gson().fromJson(value, Class.forName(OsrsFlipperSyncPlugin.class.getName() + "$OverviewResponse"));
    }

    private static boolean isComplete(Object response) throws Exception
    {
        Method method = response.getClass().getDeclaredMethod("isComplete");
        method.setAccessible(true);
        return (Boolean) method.invoke(response);
    }

    private static RuneliteOverviewView toView(Object response) throws Exception
    {
        Method method = response.getClass().getDeclaredMethod("toView");
        method.setAccessible(true);
        return (RuneliteOverviewView) method.invoke(response);
    }

    private static Object field(Object target, String name) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static JLabel label(Object target, String name) throws Exception
    {
        return (JLabel) field(target, name);
    }

    private static String text(Container container)
    {
        StringBuilder result = new StringBuilder();
        for (Component child : container.getComponents())
        {
            if (child instanceof JLabel) result.append(((JLabel) child).getText()).append('\n');
            if (child instanceof Container) result.append(text((Container) child));
        }
        return result.toString();
    }

    private static void onEdt(CheckedAction action) throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
            try { action.run(); }
            catch (Exception error) { throw new AssertionError(error); }
        });
    }

    private interface CheckedAction { void run() throws Exception; }
}
