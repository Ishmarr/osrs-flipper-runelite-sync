package com.osrsflipper.sync;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class PanelUpdateRegressionTest
{
    private OsrsFlipperSyncPanel panel;

    @Before
    public void createPanel() throws Exception
    {
        onEdt(() -> panel = new OsrsFlipperSyncPanel(
            null, () -> {}, () -> {}, () -> {}, () -> {}, ignored -> {}));
    }

    @After
    public void disposePanel() throws Exception
    {
        onEdt(() -> panel.dispose());
    }

    @Test
    public void pluginRefreshBuildsEachSectionOnceFromTheSameSnapshot() throws Exception
    {
        RuneliteOverviewView old = overview(303, 111, false);
        onEdt(() -> panel.updateView(new FlipperPanelView(Collections.emptyList(), old,
            Collections.emptyMap(), 303, "buy", old.hourly.get(0), "Previous status")));
        onEdt(() -> ((JComboBox<?>) get(panel, "statsPeriod")).setSelectedIndex(1));

        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        RuneliteOverviewView next = overview(202, 2_000_000, true);
        set(plugin, "panel", panel);
        set(plugin, "overview", next);
        addOfferSnapshot(plugin);
        LastTradePriceBook prices = (LastTradePriceBook) get(plugin, "lastTradePrices");
        prices.recordTransition(202, "buy", 0, 0, 1, 80, 1, "completed", 80, 10);
        prices.recordTransition(202, "sell", 0, 0, 1, 140, 1, "completed", 140, 11);
        SyncHealthTracker health = (SyncHealthTracker) get(plugin, "syncHealth");
        health.fail(SyncHealthTracker.Channel.OVERVIEW, "netwerkfout", 1000);
        String expectedHealth = health.banner(0);

        List<Changes> changes = new ArrayList<>();
        onEdt(() -> {
            for (String field : new String[]{"slotsList", "opportunitiesList", "statsItemsList"})
            {
                Container container = (Container) get(panel, field);
                Changes change = new Changes(container);
                changes.add(change);
                container.addContainerListener(change);
            }
        });
        Method refresh = OsrsFlipperSyncPlugin.class.getDeclaredMethod("refreshSidePanel");
        refresh.setAccessible(true);
        refresh.invoke(plugin);
        onEdt(() -> {
            for (Changes change : changes)
            {
                assertEquals("Old components are removed only once", change.before, change.removed);
                assertEquals("Final components are constructed only once", change.container.getComponentCount(), change.added);
                for (ObservedState state : change.observations)
                {
                    assertSame(next, state.overview);
                    assertEquals(0, state.focusedItemId);
                    assertEquals(80, state.lastBuyPrice);
                    assertEquals(expectedHealth, state.healthText);
                }
            }
            assertSame(next, get(panel, "overview"));
            assertFalse(((RuneliteOverviewView) get(panel, "overview")).marketAvailable);
            assertTrue(((RuneliteOverviewView) get(panel, "overview")).marketStale);
            assertEquals(1, ((JComboBox<?>) get(panel, "statsPeriod")).getSelectedIndex());
            assertEquals("+42 GP", ((JLabel) get(panel, "statsProfit")).getText());
            assertEquals("2 000 000 GP", ((JLabel) get(panel, "cashAvailable")).getText());
            String cards = text((Container) get(panel, "opportunitiesList"));
            assertTrue(cards.contains("Item 202"));
            assertFalse(cards.contains("Item 303"));
            assertTrue(cards.contains("Marktgegevens tijdelijk niet beschikbaar"));
        });
    }

    @Test
    public void queuedModelCopiesMutableSourcesAndPreservesFocusAndMarketState() throws Exception
    {
        List<FlipperOfferView> offers = new ArrayList<>(Arrays.asList(offer(2, 102), offer(1, 101)));
        LastTradePriceView price = new LastTradePriceView(202, 80, 140, 10, 11);
        Map<Integer, LastTradePriceView> prices = new LinkedHashMap<>();
        prices.put(202, price);
        RuneliteOverviewView overview = overview(202, 1000, true);
        RuneliteOverviewView.Opportunity focused = overview.hourly.get(0);
        MarketPriceView market = new MarketPriceView(202, 151, 99, 10, 11, 12);
        Map<Integer, MarketPriceView> markets = new LinkedHashMap<>();
        markets.put(202, market);
        FlipperPanelView view = new FlipperPanelView(offers, overview, prices, 202, "sell", focused, "Warning", markets);
        assertEquals("Sorting must not modify the caller's list", 2, offers.get(0).slotNumber);
        offers.clear();
        prices.clear();
        markets.clear();
        assertThrows(UnsupportedOperationException.class, () -> view.offers.clear());
        assertThrows(UnsupportedOperationException.class, () -> view.lastTradePrices.clear());
        assertThrows(UnsupportedOperationException.class, () -> view.marketPrices.clear());

        onEdt(() -> panel.updateView(view));
        onEdt(() -> {
            List<?> displayed = (List<?>) get(panel, "offers");
            assertEquals(2, displayed.size());
            assertEquals(1, ((FlipperOfferView) displayed.get(0)).slotNumber);
            assertEquals(2, ((FlipperOfferView) displayed.get(1)).slotNumber);
            assertSame(overview, get(panel, "overview"));
            assertSame(price, ((Map<?, ?>) get(panel, "lastTradePrices")).get(202));
            assertSame(market, ((Map<?, ?>) get(panel, "marketPrices")).get(202));
            assertSame(focused, get(panel, "resolvedFocusedOpportunity"));
            assertEquals("sell", get(panel, "focusedOfferSide"));
            assertTrue(text((Container) get(panel, "opportunitiesList")).contains("Geselecteerde flip"));
            panel.updateView(new FlipperPanelView(view.offers, overview, view.lastTradePrices, 0, "", null, ""));
        });
        onEdt(() -> {
            String cards = text((Container) get(panel, "opportunitiesList"));
            assertTrue(cards.contains("Top flipwaarde"));
            assertTrue(cards.contains("Item 202"));
            assertTrue(cards.contains("Marktgegevens tijdelijk niet beschikbaar"));
            assertSame(overview, get(panel, "overview"));
            assertTrue(((Map<?, ?>) get(panel, "marketPrices")).isEmpty());
        });
    }

    @Test
    public void resetModelClearsPreviousFocusWithoutInventingLoadedMarketData() throws Exception
    {
        RuneliteOverviewView previous = overview(202, 1000, false);
        FlipperPanelView empty = new FlipperPanelView(null, null, null, -1, "sell", previous.hourly.get(0), null);
        onEdt(() -> panel.updateView(new FlipperPanelView(Collections.emptyList(), previous,
            Collections.emptyMap(), 202, "buy", previous.hourly.get(0), "Previous warning")));
        onEdt(() -> panel.updateView(empty));
        onEdt(() -> {
            assertEquals(0, get(panel, "focusedItemId"));
            assertNull(get(panel, "resolvedFocusedOpportunity"));
            assertEquals("", get(panel, "focusedOfferSide"));
            assertEquals("", get(panel, "healthText"));
            assertFalse(((RuneliteOverviewView) get(panel, "overview")).topOpportunitiesLoaded);
            String cards = text((Container) get(panel, "opportunitiesList"));
            assertTrue(cards.contains("Persoonlijke flips worden opgehaald"));
            assertFalse(cards.contains("Item 202"));
        });
        FlipperPanelView wrongItem = new FlipperPanelView(null, previous, null,
            303, "invalid side", previous.hourly.get(0), "");
        assertNull(wrongItem.focusedOpportunity);
        assertEquals("", wrongItem.focusedSide);
    }

    private final class Changes extends ContainerAdapter
    {
        final Container container;
        final int before;
        int added;
        int removed;
        final List<ObservedState> observations = new ArrayList<>();

        Changes(Container container)
        {
            this.container = container;
            this.before = container.getComponentCount();
        }

        @Override public void componentRemoved(ContainerEvent event) { removed++; }
        @Override public void componentAdded(ContainerEvent event)
        {
            added++;
            observations.add(new ObservedState());
        }
    }

    private final class ObservedState
    {
        final RuneliteOverviewView overview = (RuneliteOverviewView) get(panel, "overview");
        final int focusedItemId = (Integer) get(panel, "focusedItemId");
        final String healthText = (String) get(panel, "healthText");
        final int lastBuyPrice;

        ObservedState()
        {
            LastTradePriceView price = (LastTradePriceView) ((Map<?, ?>) get(panel, "lastTradePrices")).get(202);
            lastBuyPrice = price == null ? 0 : price.lastBuyPrice;
        }
    }

    private static RuneliteOverviewView overview(int id, long cash, boolean unavailable)
    {
        RuneliteOverviewView.Opportunity opportunity = new RuneliteOverviewView.Opportunity(
            id, "Item " + id, "cycle_profit", 100, 150, 151, 99, 10, 470, 10, 470, 470, 1000);
        RuneliteOverviewView.PeriodStats stats = new RuneliteOverviewView.PeriodStats(42, 1, 2, 3, 4, 5);
        return new RuneliteOverviewView(Collections.emptyList(), Collections.singletonList(opportunity), null,
            stats, stats, stats, Collections.emptyList(), new RuneliteOverviewView.CashBalance(cash, 0, cash, 1000),
            1000, true, !unavailable, unavailable);
    }

    private static FlipperOfferView offer(int slot, int id)
    {
        return new FlipperOfferView(slot, id, "Item " + id, "buy", 100, 10, 0,
            "active", 1000, 0, 100, 150, 151, 99);
    }

    @SuppressWarnings("unchecked")
    private static void addOfferSnapshot(OsrsFlipperSyncPlugin plugin) throws Exception
    {
        Class<?> type = Class.forName(OsrsFlipperSyncPlugin.class.getName() + "$SlotSnapshot");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object snapshot = constructor.newInstance();
        set(snapshot, "slotNumber", 1);
        set(snapshot, "itemId", 101);
        set(snapshot, "itemName", "Item 101");
        set(snapshot, "side", "buy");
        set(snapshot, "status", "active");
        set(snapshot, "price", 100);
        set(snapshot, "totalQuantity", 10);
        set(snapshot, "startedAt", 1000L);
        ((Map<Integer, Object>) get(plugin, "slotSnapshots")).put(1, snapshot);
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

    private static Object get(Object target, String name)
    {
        try
        {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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
