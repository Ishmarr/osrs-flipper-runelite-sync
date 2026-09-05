package com.osrsflipper.sync;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SellStockLinkRegressionTest
{
    @Test
    public void fallbackCannotOverrideTheCycleThatRejectsStockAcquiredAfterTheSale() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        Object buy = buy(plugin, 200);
        record(plugin, buy);
        Object sell = sell(150, 40);
        assertFalse(link(plugin, sell));
        assertEquals("", get(sell, "sourceBuyOfferId"));
        assertEquals(0, get(sell, "lowestSellPrice"));
    }

    @Test
    public void fallbackCannotReuseACompletedSaleAfterItsSlotWasCollected() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        record(plugin, buy(plugin, 110));
        cycles(plugin).recordSell("buy-1", "collected-sale", 3, 3004, "Test item",
            1_000, 1_100, 1_020, 40, 40, "completed", 120, 130);
        // There is no visible sale snapshot left to exclude this buy through
        // linkedBuyOfferIds(). The persistent cycle is still authoritative.
        assertFalse(link(plugin, sell(150, 40)));
    }

    @Test
    public void fallbackCannotSpendStockAlreadyReservedInAnotherSell() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        record(plugin, buy(plugin, 110));
        cycles(plugin).recordSell("buy-1", "reserved-sale", 3, 3004, "Test item",
            1_000, 1_100, 1_020, 30, 0, "active", 120, 130);
        assertFalse(link(plugin, sell(150, 40)));
        assertTrue(link(plugin, sell(150, 10)));
    }

    @Test
    public void laterGuidanceChangesDoNotHideStockAcquiredBeforeTheSale() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        Object buy = buy(plugin, 110);
        record(plugin, buy);
        set(buy, "lastEventAt", 200L);
        set(buy, "suggestedSellPrice", 1_200);
        record(plugin, buy);
        Object sell = sell(150, 40);
        assertTrue(link(plugin, sell));
        assertEquals("buy-1", get(sell, "sourceBuyOfferId"));
        assertEquals(1_000, get(sell, "suggestedBuyPrice"));
    }

    @Test
    public void legacySnapshotStillRecoversWhenItsStockWasObservedBeforeTheSale() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        buy(plugin, 110);
        Object sell = sell(150, 40);
        assertTrue(link(plugin, sell));
        assertEquals("buy-1", get(sell, "sourceBuyOfferId"));
    }

    @Test
    public void legacyCycleWithMissingFloorRecoversOnlyItsAvailableStock() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        Object buy = buy(plugin, 110);
        set(buy, "lowestSellPrice", 0);
        record(plugin, buy);
        cycles(plugin).recordSell("buy-1", "reserved-sale", 3, 3004, "Test item",
            1_000, 1_100, 0, 30, 0, "active", 120, 130);
        assertFalse(link(plugin, sell(150, 40)));
        Object sell = sell(150, 10);
        assertTrue(link(plugin, sell));
        assertEquals(1_020, get(sell, "lowestSellPrice"));
    }

    private static Object buy(OsrsFlipperSyncPlugin plugin, long observedAt) throws Exception
    {
        Object buy = snapshot("buy", 100, 40);
        set(buy, "lastEventAt", observedAt);
        set(buy, "filledQuantity", 40);
        set(buy, "sourceBuyOfferId", "buy-1");
        set(buy, "suggestedBuyPrice", 1_000);
        set(buy, "suggestedSellPrice", 1_100);
        set(buy, "lowestSellPrice", 1_020);
        @SuppressWarnings("unchecked")
        Map<Integer, Object> slots = (Map<Integer, Object>) get(plugin, "slotSnapshots");
        slots.put(1, buy);
        return buy;
    }

    private static Object sell(long startedAt, int quantity) throws Exception
    {
        return snapshot("sell", startedAt, quantity);
    }

    private static Object snapshot(String side, long startedAt, int quantity) throws Exception
    {
        Constructor<?> constructor = Class.forName(OsrsFlipperSyncPlugin.class.getName() + "$SlotSnapshot")
            .getDeclaredConstructor();
        constructor.setAccessible(true);
        Object snapshot = constructor.newInstance();
        set(snapshot, "slotNumber", "buy".equals(side) ? 1 : 2);
        set(snapshot, "itemId", 3004);
        set(snapshot, "itemName", "Test item");
        set(snapshot, "side", side);
        set(snapshot, "price", 1_100);
        set(snapshot, "totalQuantity", quantity);
        set(snapshot, "status", "active");
        set(snapshot, "offerId", side + "-1");
        set(snapshot, "sourceBuyOfferId", "");
        set(snapshot, "startedAt", startedAt);
        set(snapshot, "lastEventAt", startedAt);
        return snapshot;
    }

    private static boolean link(OsrsFlipperSyncPlugin plugin, Object snapshot) throws Exception
    {
        return (boolean) invoke(plugin, "tryLinkSellToOpenCycle", snapshot);
    }

    private static void record(OsrsFlipperSyncPlugin plugin, Object snapshot) throws Exception
    {
        invoke(plugin, "recordFlipCycle", snapshot);
    }

    private static Object invoke(OsrsFlipperSyncPlugin plugin, String name, Object snapshot) throws Exception
    {
        Method method = OsrsFlipperSyncPlugin.class.getDeclaredMethod(name, snapshot.getClass());
        method.setAccessible(true);
        return method.invoke(plugin, snapshot);
    }

    private static FlipCyclePlanBook cycles(OsrsFlipperSyncPlugin plugin) throws Exception
    {
        return (FlipCyclePlanBook) get(plugin, "flipCycles");
    }

    private static Object get(Object target, String name) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
