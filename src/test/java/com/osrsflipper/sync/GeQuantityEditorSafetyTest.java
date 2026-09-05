package com.osrsflipper.sync;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeQuantityEditorSafetyTest
{
    @Test
    public void positiveZeroPositiveDisablesAndRestoresOnlyOurSuggestion() throws Exception
    {
        Harness h = new Harness();
        WidgetStub other = new WidgetStub("Another plugin - 999");
        other.actions.put(1, "Other action");
        h.parent.children.add(other);
        h.overview(101, 11000, 11000);
        h.showQuantity();
        WidgetStub suggestion = h.ownSuggestion();
        assertFalse(suggestion.hidden);
        assertTrue(suggestion.hasListener);
        assertTrue(suggestion.text.endsWith("11,000"));

        h.overview(101, 0, 11000);
        h.showQuantity();
        assertTrue(suggestion.hidden);
        assertFalse(suggestion.hasListener);
        assertTrue(suggestion.actions.isEmpty());
        assertNull(suggestion.listeners.get("setOnOpListener"));
        assertNull(suggestion.listeners.get("setOnMouseRepeatListener"));
        assertNull(suggestion.listeners.get("setOnMouseLeaveListener"));
        assertEquals("123*", h.input.text);
        assertEquals("123", h.inputValue);
        assertFalse(other.hidden);
        assertEquals("Other action", other.actions.get(1));
        assertEquals("Another plugin - 999", other.text);

        h.overview(101, 500, 11000);
        h.showQuantity();
        assertSame(suggestion, h.ownSuggestion());
        assertFalse(suggestion.hidden);
        assertTrue(suggestion.hasListener);
        assertTrue(suggestion.text.endsWith("500"));
        h.callback().run(null);
        assertEquals("500", h.inputValue);
    }

    @Test
    public void delayedClickUsesLatestQuantityAndRemainingLimitNotCapturedValue() throws Exception
    {
        Harness h = new Harness();
        h.overview(101, 11000, 11000);
        h.showQuantity();
        JavaScriptCallback delayed = h.callback();
        h.overview(101, 900, 17);
        delayed.run(null);
        assertEquals("17", h.inputValue);
        assertEquals("17*", h.input.text);
    }

    @Test
    public void delayedClickAfterZeroOrUnknownQuantityDoesNotChangeManualInput() throws Exception
    {
        for (int quantity : new int[] {0, -1})
        {
            Harness h = new Harness();
            h.overview(101, 11000, 11000);
            h.showQuantity();
            JavaScriptCallback delayed = h.callback();
            h.overview(101, quantity, 11000);
            delayed.run(null);
            assertEquals("123", h.inputValue);
            assertEquals("123*", h.input.text);
            assertTrue(h.ownSuggestion().hidden);
            assertTrue(h.ownSuggestion().actions.isEmpty());
        }
    }

    @Test
    public void unknownQuantityHidesAnExistingSuggestion() throws Exception
    {
        Harness h = new Harness();
        h.overview(101, 11000, 11000);
        h.showQuantity();
        h.overview(101, -1, 11000);
        h.showQuantity();
        assertTrue(h.ownSuggestion().hidden);
        assertNull(h.ownSuggestion().listeners.get("setOnOpListener"));
    }

    @Test
    public void itemSwitchRejectsOldCallbackAndFreshSuggestionUsesActualSetupItem() throws Exception
    {
        Harness h = new Harness();
        h.overview(101, 11000, 11000);
        h.showQuantity();
        JavaScriptCallback delayed = h.callback();
        h.setupItem.itemId = 202;
        // The old search/focus values intentionally remain 101 until RuneLite updates them.
        h.overview(202, 72, 11000);
        delayed.run(null);
        assertEquals("123", h.inputValue);
        assertTrue(h.ownSuggestion().hidden);
        h.showQuantity();
        assertFalse(h.ownSuggestion().hidden);
        assertTrue(h.ownSuggestion().text.endsWith("72"));
        h.callback().run(null);
        assertEquals("72", h.inputValue);
    }

    @Test
    public void priceEditorRestoresHiddenSharedWidgetAndQuantityCleanupLeavesItAlone() throws Exception
    {
        Harness h = new Harness();
        h.overview(101, 11000, 11000);
        h.showQuantity();
        JavaScriptCallback oldQuantity = h.callback();
        h.overview(101, 0, 11000);
        h.showQuantity();
        WidgetStub shared = h.ownSuggestion();
        assertTrue(shared.hidden);
        h.prompt.text = "Set a price for each item:";
        h.invoke("showGePriceEditorSuggestion");
        assertSame(shared, h.ownSuggestion());
        assertFalse(shared.hidden);
        assertTrue(shared.text.contains("Koopprijs"));
        assertTrue(shared.hasListener);
        JavaScriptCallback priceCallback = h.callback();
        h.showQuantity();
        oldQuantity.run(null);
        assertFalse(shared.hidden);
        assertSame(priceCallback, h.callback());
        assertEquals("123", h.inputValue);
        priceCallback.run(null);
        assertEquals("100", h.inputValue);
    }

    @Test
    public void sellOrHiddenSetupRejectsStaleQuantityClick() throws Exception
    {
        for (boolean hidden : new boolean[] {false, true})
        {
            Harness h = new Harness();
            h.overview(101, 11000, 11000);
            h.showQuantity();
            JavaScriptCallback delayed = h.callback();
            h.setup.hidden = hidden;
            if (!hidden) h.setup.text = "Sell offer";
            delayed.run(null);
            assertEquals("123", h.inputValue);
            assertTrue(h.ownSuggestion().hidden);
        }
    }

    @Test
    public void priceClickUsesNewestLocalWikiPriceWithoutStartingNetworkWork() throws Exception
    {
        Harness h = new Harness();
        h.overview(101, 100, 100);
        h.prompt.text = "Set a price for each item:";
        h.invoke("showGePriceEditorSuggestionFromCache");
        JavaScriptCallback delayed = h.callback();
        h.market(101, 250, 199);
        delayed.run(null);
        assertEquals("200", h.inputValue);
        // No HTTP client/client-thread dispatcher is injected: a refresh on
        // this click would fail instead of silently doing extra network work.
    }

    @Test
    public void lateWikiResponseCreatesAndRefreshesPriceRuleInAnOpenEditor() throws Exception
    {
        Harness h = new Harness();
        h.prompt.text = "Set a price for each item:";
        h.invoke("refreshGeEditorSuggestions");
        assertTrue(h.parent.children.isEmpty());
        h.wikiResponse(101, 250, 99);
        WidgetStub suggestion = h.ownSuggestion();
        assertTrue(suggestion.text.endsWith("100 gp"));
        h.wikiResponse(101, 250, 199);
        assertSame(suggestion, h.ownSuggestion());
        assertTrue(suggestion.text.endsWith("200 gp"));
        h.callback().run(null);
        assertEquals("200", h.inputValue);
    }

    @Test
    public void missingPriceDisablesRuleAndStaleClickPreservesManualInput() throws Exception
    {
        Harness h = new Harness();
        h.overview(101, 100, 100);
        h.prompt.text = "Set a price for each item:";
        h.invoke("showGePriceEditorSuggestionFromCache");
        JavaScriptCallback delayed = h.callback();
        set(h.plugin, "overview", RuneliteOverviewView.empty());
        h.invoke("refreshGeEditorSuggestions");
        assertTrue(h.ownSuggestion().hidden);
        assertFalse(h.ownSuggestion().hasListener);
        assertTrue(h.ownSuggestion().actions.isEmpty());
        delayed.run(null);
        assertEquals("123", h.inputValue);
        h.overview(101, 100, 100);
        h.invoke("refreshGeEditorSuggestions");
        assertFalse(h.ownSuggestion().hidden);
        h.callback().run(null);
        assertEquals("100", h.inputValue);
    }

    @Test
    public void oldPriceClickCannotEditAnotherItemSideSlotOrPrompt() throws Exception
    {
        for (String change : new String[] {"item", "side", "slot", "prompt", "hidden"})
        {
            Harness h = new Harness();
            h.overview(101, 100, 100);
            h.prompt.text = "Set a price for each item:";
            h.invoke("showGePriceEditorSuggestionFromCache");
            JavaScriptCallback delayed = h.callback();
            switch (change)
            {
                case "item": h.setupItem.itemId = 202; break;
                case "side": h.setup.text = "Sell offer"; break;
                case "slot": h.selectedSlot = 2; break;
                case "prompt": h.prompt.text = "How many do you wish to buy?"; break;
                case "hidden": h.setup.hidden = true; break;
                default: throw new AssertionError(change);
            }
            delayed.run(null);
            assertEquals(change, "123", h.inputValue);
            assertTrue(change, h.ownSuggestion().hidden);
        }
    }

    @Test
    public void oldPriceCallbacksAfterRestartOrConnectionSwitchAreIgnored() throws Exception
    {
        for (String generation : new String[] {"lifecycleGeneration", "overviewContextGeneration"})
        {
            Harness h = new Harness();
            h.overview(101, 100, 100);
            h.prompt.text = "Set a price for each item:";
            h.invoke("showGePriceEditorSuggestionFromCache");
            JavaScriptCallback delayed = h.callback();
            set(h.plugin, generation, 1L);
            delayed.run(null);
            assertEquals(generation, "123", h.inputValue);
        }
    }

    @Test
    public void quantityRuleSurvivesOldPriceCallbackAndPriceCleanup() throws Exception
    {
        Harness h = new Harness();
        h.overview(101, 100, 100);
        h.prompt.text = "Set a price for each item:";
        h.invoke("showGePriceEditorSuggestionFromCache");
        JavaScriptCallback oldPrice = h.callback();
        h.prompt.text = "How many do you wish to buy?";
        h.invoke("refreshGeEditorSuggestions");
        JavaScriptCallback quantity = h.callback();
        oldPrice.run(null);
        assertFalse(h.ownSuggestion().hidden);
        assertSame(quantity, h.callback());
        assertEquals("123", h.inputValue);
    }

    private static final class Harness
    {
        final OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        final WidgetStub prompt = new WidgetStub("How many do you wish to buy?");
        final WidgetStub parent = new WidgetStub("");
        final WidgetStub setup = new WidgetStub("Buy offer");
        final WidgetStub setupItem = new WidgetStub("");
        final WidgetStub input = new WidgetStub("123*");
        String inputValue = "123";
        int selectedSlot;

        Harness() throws Exception
        {
            setupItem.itemId = 101;
            Map<Integer, Widget> widgets = new HashMap<>();
            widgets.put(InterfaceID.Chatbox.MES_TEXT, prompt.widget);
            widgets.put(InterfaceID.Chatbox.MES_LAYER, parent.widget);
            widgets.put(InterfaceID.Chatbox.MES_TEXT2, input.widget);
            widgets.put(InterfaceID.GeOffers.SETUP, setup.widget);
            widgets.put(InterfaceID.GeOffers.SETUP_GRAPHIC4, setupItem.widget);
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
                new Class<?>[] {Client.class}, (proxy, method, arguments) ->
                {
                    switch (method.getName())
                    {
                        case "getWidget": return widgets.get((Integer) arguments[0]);
                        case "getVarpValue": return 101;
                        case "getVarbitValue": return selectedSlot;
                        case "setVarcStrValue": inputValue = (String) arguments[1]; return null;
                        default: return defaultValue(method.getReturnType());
                    }
                });
            set(plugin, "client", client);
            set(plugin, "started", true);
            set(plugin, "gson", new com.google.gson.Gson());
            set(plugin, "focusedGeItemId", 101);
            set(plugin, "focusedGeSide", "buy");
            set(plugin, "focusedGeContext", FocusedGeItemResolver.EditorContext.NEW_SETUP);
        }

        void overview(int itemId, int quantity, int remaining) throws Exception
        {
            RuneliteOverviewView.Opportunity row = new RuneliteOverviewView.Opportunity(
                itemId, "Test item", "focus", 100, 200, 201, 99,
                quantity, 0, quantity, 0, 0, 100, 11000, 11000 - remaining, remaining);
            set(plugin, "overview", new RuneliteOverviewView(
                Collections.emptyList(), Collections.emptyList(), row, null, null, null, 100));
        }

        void showQuantity() throws Exception { invoke("showGeQuantityEditorSuggestion"); }

        @SuppressWarnings("unchecked")
        void market(int itemId, int instantBuy, int instantSell) throws Exception
        {
            Field prices = OsrsFlipperSyncPlugin.class.getDeclaredField("marketPrices");
            prices.setAccessible(true);
            ((Map<Integer, MarketPriceView>) prices.get(plugin)).put(itemId,
                new MarketPriceView(itemId, instantBuy, instantSell, 100, 100, 100));
        }

        void wikiResponse(int itemId, int instantBuy, int instantSell) throws Exception
        {
            Method response = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
                "handleMarketPriceResponse", int.class, int.class, String.class);
            response.setAccessible(true);
            response.invoke(plugin, itemId, 200, "{\"data\":{\"" + itemId +
                "\":{\"high\":" + instantBuy + ",\"low\":" + instantSell +
                ",\"highTime\":100,\"lowTime\":100}}}");
        }

        void invoke(String name) throws Exception
        {
            Method method = OsrsFlipperSyncPlugin.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(plugin);
        }

        WidgetStub ownSuggestion()
        {
            return parent.children.stream().filter(child -> child.text.startsWith("OSRS Flip Tracker - "))
                .findFirst().orElseThrow(AssertionError::new);
        }

        JavaScriptCallback callback()
        {
            return (JavaScriptCallback) ownSuggestion().listeners.get("setOnOpListener")[0];
        }
    }

    private static final class WidgetStub implements InvocationHandler
    {
        final Widget widget;
        final List<WidgetStub> children = new ArrayList<>();
        final Map<Integer, String> actions = new HashMap<>();
        final Map<String, Object[]> listeners = new HashMap<>();
        String text;
        int itemId;
        boolean hidden;
        boolean hasListener;

        WidgetStub(String text)
        {
            this.text = text;
            widget = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
                new Class<?>[] {Widget.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments)
        {
            switch (method.getName())
            {
                case "getText": return text;
                case "setText": text = (String) arguments[0]; break;
                case "getItemId": return itemId;
                case "isHidden": return hidden;
                case "setHidden": hidden = (Boolean) arguments[0]; break;
                case "setHasListener": hasListener = (Boolean) arguments[0]; break;
                case "setAction": actions.put((Integer) arguments[0], (String) arguments[1]); break;
                case "clearActions": actions.clear(); break;
                case "setOnOpListener":
                case "setOnMouseRepeatListener":
                case "setOnMouseLeaveListener": listeners.put(method.getName(), (Object[]) arguments[0]); break;
                case "getChildren":
                case "getDynamicChildren": return children.stream().map(child -> child.widget).toArray(Widget[]::new);
                case "createChild":
                    WidgetStub child = new WidgetStub("");
                    children.add(child);
                    return child.widget;
                default: break;
            }
            return method.getReturnType() == Widget.class ? widget : defaultValue(method.getReturnType());
        }
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object defaultValue(Class<?> type)
    {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
