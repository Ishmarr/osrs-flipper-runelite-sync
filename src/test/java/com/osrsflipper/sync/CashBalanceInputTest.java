package com.osrsflipper.sync;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CashBalanceInputTest
{
    @Test
    public void acceptsWholeGpAndCorrectlyGroupedSpacesIncludingPastedSpaces()
    {
        for (String input : new String[] {"1000000", "1 000 000", " 1 000 000 ",
            "1\u00a0000\u00a0000", "1\u202f000\u202f000"})
        {
            assertEquals(input, 1_000_000L, OsrsFlipperSyncPanel.parseCashBalance(input));
        }
        assertEquals(0, OsrsFlipperSyncPanel.parseCashBalance("0"));
        assertEquals(Integer.MAX_VALUE, OsrsFlipperSyncPanel.parseCashBalance("2 147 483 647"));
    }

    @Test
    public void rejectsTheEntireInvalidInputInsteadOfDeletingMeaningfulCharacters()
    {
        for (String input : invalidInputs())
        {
            try
            {
                OsrsFlipperSyncPanel.parseCashBalance(input);
                fail("Accepted invalid cash: " + input);
            }
            catch (NumberFormatException expected)
            {
                // Invalid data must never become a different valid balance.
            }
        }
    }

    @Test
    public void invalidEditorSubmissionNeverCallsTheAccountCashAction() throws Exception
    {
        List<Long> submitted = new ArrayList<>();
        SwingUtilities.invokeAndWait(() ->
        {
            OsrsFlipperSyncPanel panel = new OsrsFlipperSyncPanel(
                null, () -> {}, () -> {}, () -> {}, () -> {}, submitted::add);
            try
            {
                Field cash = OsrsFlipperSyncPanel.class.getDeclaredField("cashInput");
                cash.setAccessible(true);
                JTextField input = (JTextField) cash.get(panel);
                for (String invalid : invalidInputs())
                {
                    input.setText(invalid);
                    input.postActionEvent();
                    assertTrue(input.getToolTipText().startsWith("Ongeldige invoer"));
                }
                assertTrue(submitted.isEmpty());
                input.setText("1 000 000");
                input.postActionEvent();
                assertEquals(Arrays.asList(1_000_000L), submitted);
                assertTrue(input.getToolTipText().startsWith("Gehele GP"));
            }
            catch (ReflectiveOperationException exception)
            {
                throw new AssertionError(exception);
            }
            finally
            {
                panel.dispose();
            }
        });
    }

    private static String[] invalidInputs()
    {
        return new String[] {null, "", " ", "-100", "+100", "1.5m", "100abc", "1,5",
            "1.000", "1,000", "1 00", "12 34 567", "1  000", "100 GP", "1e6",
            "2 147 483 648", "2147483648", "999999999999999999999999"};
    }
}
