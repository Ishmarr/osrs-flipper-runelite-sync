package com.osrsflipper.sync;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import net.runelite.client.ui.laf.RuneLiteLAF;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class OpportunitiesPanelStateTest
{
    private OsrsFlipperSyncPanel panel;
    private LookAndFeel previousLookAndFeel;

    @Before
    public void createPanel() throws Exception
    {
        onEdt(() -> {
            previousLookAndFeel = UIManager.getLookAndFeel();
            UIManager.setLookAndFeel(new RuneLiteLAF());
            panel = new OsrsFlipperSyncPanel(
                null, () -> {}, () -> {}, () -> {}, () -> {}, ignored -> {});
        });
    }

    @After
    public void disposePanel() throws Exception
    {
        onEdt(() -> {
            try
            {
                if (panel != null) panel.dispose();
            }
            finally
            {
                UIManager.setLookAndFeel(previousLookAndFeel);
            }
        });
    }

    @Test
    public void coldStartAndFocusOnlyDataKeepGlobalListInLoadingState() throws Exception
    {
        onEdt(() -> assertTrue(renderText().contains("Persoonlijke flips worden opgehaald")));
        RuneliteOverviewView.Opportunity focused = opportunity(385, "Anglerfish", 900);
        RuneliteOverviewView focusOnly = new RuneliteOverviewView(
            Collections.emptyList(), Collections.emptyList(), focused,
            RuneliteOverviewView.PeriodStats.empty(), RuneliteOverviewView.PeriodStats.empty(),
            RuneliteOverviewView.PeriodStats.empty(), Collections.emptyList(),
            new RuneliteOverviewView.CashBalance(1_000_000, 0, 1_000_000, 1000),
            0, false, true, false);
        show(focusOnly);
        onEdt(() -> {
            assertTrue(renderText().contains("Persoonlijke flips worden opgehaald"));
            panel.updateFocusedItem(385, "buy", focused);
        });
        onEdt(() -> {
            assertTrue(renderText().contains("Anglerfish"));
            panel.updateFocusedItem(0);
        });
        onEdt(() -> {
            String text = renderText();
            assertTrue(text.contains("Persoonlijke flips worden opgehaald"));
            assertFalse(text.contains("Nog geen uitvoerbare flip"));
            assertFalse(text.contains("Marktprijzen:"));
        });
    }

    @Test
    public void unavailableColdStartDoesNotClaimAValidEmptyMarket() throws Exception
    {
        show(view(Collections.emptyList(), 0, false, false, true, 0));
        onEdt(() -> {
            String text = renderText();
            assertTrue(text.contains("Flips tijdelijk niet beschikbaar"));
            assertFalse(text.contains("Nog geen uitvoerbare flip"));
            assertFalse(text.contains("Geen vrije GP"));
            assertFalse(text.contains("Marktprijzen:"));
        });
    }

    @Test
    public void healthyEmptyMarketExplainsThresholdOrMissingFreeCash() throws Exception
    {
        show(view(Collections.emptyList(), 1000, true, true, false, 1_000_000));
        onEdt(() -> {
            String text = renderText();
            assertTrue(text.contains("Nog geen uitvoerbare flip met minstens 100k GP flipwaarde"));
            assertFalse(text.contains("tijdelijk niet beschikbaar"));
            assertFalse(text.contains("verouderd"));
        });
        show(view(Collections.emptyList(), 1000, true, true, false, 0));
        onEdt(() -> {
            String text = renderText();
            assertTrue(text.contains("Geen vrije GP beschikbaar voor een nieuwe flip"));
            assertTrue(text.contains("Controleer je cash en GE-offers"));
            assertFalse(text.contains("Nog geen uitvoerbare flip"));
        });
    }

    @Test
    public void staleEmptyMarketDoesNotClaimThereAreNoExecutableFlips() throws Exception
    {
        show(view(Collections.emptyList(), Instant.now().getEpochSecond() - 3600,
            true, true, true, 1_000_000));
        onEdt(() -> {
            String text = renderText();
            assertTrue(text.contains("Marktprijzen zijn verouderd"));
            assertTrue(text.contains("Wacht op actuele marktprijzen om uitvoerbare flips te bepalen"));
            assertFalse(text.contains("Nog geen uitvoerbare flip"));
            assertTrue(text.contains("Marktprijzen:"));
            assertFalse(text.contains("Bijgewerkt"));
            Path output = Paths.get("build/reports/opportunities-stale-empty-panel.png");
            Files.createDirectories(output.getParent());
            ImageIO.write(render(), "png", output.toFile());
        });
    }

    @Test
    public void oldCardsRemainVisibleWithUnavailableOrStaleWarningAndRecover() throws Exception
    {
        long marketUpdatedAt = Instant.now().getEpochSecond() - 3600;
        List<RuneliteOverviewView.Opportunity> opportunities = Collections.singletonList(
            opportunity(4151, "Abyssal whip", marketUpdatedAt));
        show(view(opportunities, marketUpdatedAt, true, false, true, 1_000_000));
        onEdt(() -> {
            String text = renderText();
            assertTrue(text.contains("Abyssal whip"));
            assertTrue(text.contains("Marktgegevens tijdelijk niet beschikbaar"));
            assertTrue(text.contains("laatst opgehaalde flips"));
        });
        show(view(opportunities, marketUpdatedAt, true, true, true, 1_000_000));
        onEdt(() -> {
            String text = renderText();
            assertTrue(text.contains("Abyssal whip"));
            assertTrue(text.contains("Marktprijzen zijn verouderd"));
            assertFalse(text.contains("tijdelijk niet beschikbaar"));
            Path output = Paths.get("build/reports/opportunities-stale-panel.png");
            Files.createDirectories(output.getParent());
            ImageIO.write(render(), "png", output.toFile());
        });
        show(view(opportunities, marketUpdatedAt + 3600, true, true, false, 1_000_000));
        onEdt(() -> {
            String text = renderText();
            assertTrue(text.contains("Abyssal whip"));
            assertFalse(text.contains("verouderd"));
            assertFalse(text.contains("tijdelijk niet beschikbaar"));
        });
    }

    @Test
    public void unavailableEmptyCacheExplainsThereAreNoSavedCards() throws Exception
    {
        show(view(Collections.emptyList(), 1000, true, false, true, 1_000_000));
        onEdt(() -> {
            String text = renderText();
            assertTrue(text.contains("Geen bewaarde flips om te tonen"));
            assertTrue(text.contains("Nieuwe gegevens worden opgehaald"));
            assertFalse(text.contains("Je ziet de laatst opgehaalde flips"));
            assertFalse(text.contains("Nog geen uitvoerbare flip"));
        });
    }

    @Test
    public void allCandidatesAlreadyInGeAreDifferentFromAnEmptyScannerResult() throws Exception
    {
        show(view(Collections.singletonList(opportunity(4151, "Abyssal whip", 1000)),
            1000, true, true, false, 1_000_000));
        onEdt(() -> panel.updateOffers(Collections.singletonList(new FlipperOfferView(
            1, 4151, "Abyssal whip", "buy", 100, 10, 0,
            "active", 1, 0, 100, 110, 111, 99))));
        onEdt(() -> {
            String text = renderText();
            assertTrue(text.contains("Alle gevonden flips staan al in je GE-slots"));
            assertFalse(text.contains("Nog geen uitvoerbare flip"));
            assertFalse(text.contains("Abyssal whip"));
            panel.updateOffers(Collections.emptyList());
        });
        onEdt(() -> {
            assertTrue(renderText().contains("Abyssal whip"));
            assertFalse(renderText().contains("Alle gevonden flips"));
        });
    }

    @Test
    public void existingClockAdvancesPriceAgeWithoutRebuildingCards() throws Exception
    {
        show(view(Collections.singletonList(opportunity(4151, "Abyssal whip", 1000)),
            1000, true, true, false, 1_000_000));
        onEdt(() -> {
            Component[] before = opportunities().getComponents();
            tick(1060);
            assertTrue(text(opportunities()).contains("Marktprijzen: 1 min geleden"));
            tick(4600);
            assertTrue(text(opportunities()).contains("Marktprijzen: 1 u geleden"));
            assertArrayEquals(before, opportunities().getComponents());
        });
    }

    @Test
    public void focusedPricesUseTheirOwnTimestampAndNoUnrelatedGlobalWarning() throws Exception
    {
        show(view(Collections.emptyList(), 1000, true, true, true, 1_000_000));
        onEdt(() -> panel.updateFocusedItem(385, "buy", opportunity(385, "Anglerfish", 4590)));
        onEdt(() -> {
            tick(4600);
            String text = renderText();
            assertTrue(text.contains("Itemprijzen: 10 sec geleden"));
            assertFalse(text.contains("Marktprijzen:"));
            assertFalse(text.contains("verouderd"));
            panel.updateFocusedItem(385, "buy", opportunity(385, "Anglerfish", 0));
        });
        onEdt(() -> {
            assertTrue(renderText().contains("Anglerfish"));
            assertFalse(renderText().contains("Itemprijzen:"));
        });
    }

    private void show(RuneliteOverviewView view) throws Exception
    {
        onEdt(() -> panel.updateOverview(view));
        onEdt(() -> {});
    }

    private String renderText() throws Exception
    {
        render();
        return text(opportunities());
    }

    private BufferedImage render() throws Exception
    {
        assertTrue(UIManager.getLookAndFeel() instanceof RuneLiteLAF);
        Method select = OsrsFlipperSyncPanel.class.getDeclaredMethod("selectTab", String.class);
        select.setAccessible(true);
        select.invoke(panel, "opportunities");
        panel.setSize(240, 800);
        layout(panel);
        BufferedImage image = new BufferedImage(240, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try
        {
            panel.paint(graphics);
        }
        finally
        {
            graphics.dispose();
        }
        return image;
    }

    private Container opportunities() throws Exception
    {
        Field field = OsrsFlipperSyncPanel.class.getDeclaredField("opportunitiesList");
        field.setAccessible(true);
        return (Container) field.get(panel);
    }

    private void tick(long now) throws Exception
    {
        Method method = OsrsFlipperSyncPanel.class.getDeclaredMethod("updateClocks", long.class);
        method.setAccessible(true);
        method.invoke(panel, now);
    }

    private static RuneliteOverviewView view(
        List<RuneliteOverviewView.Opportunity> opportunities,
        long generatedAt,
        boolean loaded,
        boolean available,
        boolean stale,
        long cash)
    {
        return new RuneliteOverviewView(Collections.emptyList(), opportunities, null,
            RuneliteOverviewView.PeriodStats.empty(), RuneliteOverviewView.PeriodStats.empty(),
            RuneliteOverviewView.PeriodStats.empty(), Collections.emptyList(),
            new RuneliteOverviewView.CashBalance(cash, 0, cash, 1000),
            generatedAt, loaded, available, stale);
    }

    private static RuneliteOverviewView.Opportunity opportunity(int id, String name, long timestamp)
    {
        return new RuneliteOverviewView.Opportunity(id, name, "cycle_profit",
            100, 130, 131, 99, 100, 2500, 100, 5000, 2500, timestamp);
    }

    private static void layout(Container container)
    {
        container.doLayout();
        for (Component child : container.getComponents())
        {
            if (child instanceof Container) layout((Container) child);
        }
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
