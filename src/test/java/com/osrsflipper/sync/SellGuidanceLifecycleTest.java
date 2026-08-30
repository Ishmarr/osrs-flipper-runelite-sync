package com.osrsflipper.sync;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SellGuidanceLifecycleTest
{
    private static final int ITEM_ID = 21_622;

    @Test
    public void higherOverviewWikiUpdatesVisibleSnapshotCycleAndSyncEventTogether() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        FlipCyclePlanBook cycles = cycles(plugin);
        cycles.recordBuy(
            "buy-sea-turtle", "buy-sea-turtle", 1, ITEM_ID, "Sea turtle",
            939, 1_002, 958, 5_223, 5_223, "completed", 100, 100);
        Object snapshot = snapshot(
            1, "buy", "completed", "buy-sea-turtle", "buy-sea-turtle",
            939, 1_002, 958, 5_223, 5_223);
        snapshots(plugin).put(1, snapshot);
        marketPrices(plugin).put(
            ITEM_ID,
            new MarketPriceView(ITEM_ID, 1_000, 990, 110, 109, 111));
        overview(plugin, 1_014);

        assertTrue(refresh(plugin));

        assertEquals(939, intField(snapshot, "suggestedBuyPrice"));
        assertEquals(1_013, intField(snapshot, "suggestedSellPrice"));
        assertEquals(958, intField(snapshot, "lowestSellPrice"));
        assertEquals(1_013, cycles.cycle("buy-sea-turtle").sellTargetPrice);
        assertEquals(1, outbox(plugin).size());
        Object event = field(outbox(plugin).peekFirst(), "event");
        assertEquals(1_013, intField(event, "suggestedSellPrice"));
    }

    @Test
    public void everyVisibleSlotReceivesTheFinalMaximumRegardlessOfIterationOrder() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        FlipCyclePlanBook cycles = cycles(plugin);
        cycles.recordBuy(
            "buy-sea-turtle", "buy-sea-turtle", 1, ITEM_ID, "Sea turtle",
            939, 1_002, 958, 10, 10, "completed", 100, 100);
        Object lowerSnapshot = snapshot(
            1, "buy", "completed", "buy-sea-turtle", "buy-sea-turtle",
            939, 1_002, 958, 10, 10);
        Object higherSnapshot = snapshot(
            2, "sell", "cancelled", "sell-sea-turtle", "buy-sea-turtle",
            939, 1_020, 958, 10, 0);
        snapshots(plugin).put(1, lowerSnapshot);
        snapshots(plugin).put(2, higherSnapshot);
        overview(plugin, 1_014);

        assertTrue(refresh(plugin));

        assertEquals(1_020, cycles.cycle("buy-sea-turtle").sellTargetPrice);
        assertEquals(1_020, intField(lowerSnapshot, "suggestedSellPrice"));
        assertEquals(1_020, intField(higherSnapshot, "suggestedSellPrice"));
    }

    @Test
    public void closedCycleAndCompletedSellSnapshotIgnoreWikiRise() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        FlipCyclePlanBook cycles = cycles(plugin);
        cycles.recordBuy(
            "buy-sea-turtle", "buy-sea-turtle", 1, ITEM_ID, "Sea turtle",
            939, 1_002, 958, 10, 10, "completed", 100, 100);
        cycles.recordSell(
            "buy-sea-turtle", "sell-sea-turtle", 2, ITEM_ID, "Sea turtle",
            939, 1_002, 958, 10, 10, "completed", 200, 200);
        Object snapshot = snapshot(
            2, "sell", "completed", "sell-sea-turtle", "buy-sea-turtle",
            939, 1_002, 958, 10, 10);
        snapshots(plugin).put(2, snapshot);
        overview(plugin, 1_030);

        assertFalse(refresh(plugin));

        assertEquals(1_002, intField(snapshot, "suggestedSellPrice"));
        assertEquals(1_002, cycles.cycle("buy-sea-turtle").sellTargetPrice);
        assertEquals(1, longField(snapshot, "eventSequence"));
        assertTrue(outbox(plugin).isEmpty());
    }

    @Test
    public void cancelledPartialSellRemainsEligibleWhileInventoryIsUnsold() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        FlipCyclePlanBook cycles = cycles(plugin);
        cycles.recordBuy(
            "buy-sea-turtle", "buy-sea-turtle", 1, ITEM_ID, "Sea turtle",
            939, 1_002, 958, 10, 10, "completed", 100, 100);
        cycles.recordSell(
            "buy-sea-turtle", "sell-sea-turtle", 2, ITEM_ID, "Sea turtle",
            939, 1_002, 958, 10, 4, "cancelled", 200, 200);
        Object snapshot = snapshot(
            2, "sell", "cancelled", "sell-sea-turtle", "buy-sea-turtle",
            939, 1_002, 958, 10, 4);
        snapshots(plugin).put(2, snapshot);
        overview(plugin, 1_014);

        assertTrue(refresh(plugin));

        assertEquals(6, cycles.cycle("buy-sea-turtle").availableQuantity());
        assertEquals(1_013, cycles.cycle("buy-sea-turtle").sellTargetPrice);
        assertEquals(939, intField(snapshot, "suggestedBuyPrice"));
        assertEquals(1_013, intField(snapshot, "suggestedSellPrice"));
        assertEquals(958, intField(snapshot, "lowestSellPrice"));
        assertEquals(1, outbox(plugin).size());
    }

    @Test
    public void higherServerTargetSurvivesInLinkedCycleAndLaterLowerWiki() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        FlipCyclePlanBook cycles = cycles(plugin);
        cycles.recordBuy(
            "buy-sea-turtle", "buy-sea-turtle", 1, ITEM_ID, "Sea turtle",
            939, 1_002, 958, 10, 10, "completed", 100, 100);
        Object snapshot = snapshot(
            1, "buy", "completed", "buy-sea-turtle", "buy-sea-turtle",
            939, 1_002, 958, 10, 10);
        snapshots(plugin).put(1, snapshot);

        Object server = serverState(1, 1_020);
        invokeAdoptServerMetadata(plugin, snapshot, server);

        assertEquals(1_020, intField(snapshot, "suggestedSellPrice"));
        assertEquals(1_020, cycles.cycle("buy-sea-turtle").sellTargetPrice);

        overview(plugin, 1_014);
        assertFalse(refresh(plugin));
        assertEquals(1_020, intField(snapshot, "suggestedSellPrice"));
        assertEquals(1_020, cycles.cycle("buy-sea-turtle").sellTargetPrice);
    }

    @Test
    public void delayedServerGuidanceCannotRewriteAClosedSnapshot() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        FlipCyclePlanBook cycles = cycles(plugin);
        cycles.recordBuy(
            "buy-sea-turtle", "buy-sea-turtle", 1, ITEM_ID, "Sea turtle",
            939, 1_002, 958, 10, 10, "completed", 100, 100);
        cycles.recordSell(
            "buy-sea-turtle", "sell-sea-turtle", 2, ITEM_ID, "Sea turtle",
            939, 1_002, 958, 10, 10, "completed", 200, 200);
        Object snapshot = snapshot(
            2, "sell", "completed", "sell-sea-turtle", "buy-sea-turtle",
            939, 1_002, 958, 10, 10);
        snapshots(plugin).put(2, snapshot);
        Object server = serverState(2, 1_020);
        set(server, "runelite_offer_id", "sell-sea-turtle");
        set(server, "suggested_buy_price", 950);
        set(server, "lowest_sell_price", 970);

        invokeAdoptServerMetadata(plugin, snapshot, server);

        assertEquals(939, intField(snapshot, "suggestedBuyPrice"));
        assertEquals(1_002, intField(snapshot, "suggestedSellPrice"));
        assertEquals(958, intField(snapshot, "lowestSellPrice"));
        assertEquals(1_002, cycles.cycle("buy-sea-turtle").sellTargetPrice);
    }

    private static boolean refresh(OsrsFlipperSyncPlugin plugin) throws Exception
    {
        Method refresh = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "refreshOpenFlipSellGuidance",
            int.class);
        refresh.setAccessible(true);
        return (Boolean) refresh.invoke(plugin, ITEM_ID);
    }

    private static void invokeAdoptServerMetadata(
        OsrsFlipperSyncPlugin plugin,
        Object snapshot,
        Object server) throws Exception
    {
        Method adopt = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "adoptServerMetadata",
            int.class,
            snapshot.getClass(),
            server.getClass());
        adopt.setAccessible(true);
        adopt.invoke(plugin, 1, snapshot, server);
    }

    private static Object snapshot(
        int slot,
        String side,
        String status,
        String offerId,
        String sourceBuyOfferId,
        int buyPrice,
        int sellPrice,
        int lowestPrice,
        int totalQuantity,
        int filledQuantity) throws Exception
    {
        Object snapshot = newNested("SlotSnapshot");
        set(snapshot, "slotNumber", slot);
        set(snapshot, "itemId", ITEM_ID);
        set(snapshot, "itemName", "Sea turtle");
        set(snapshot, "side", side);
        set(snapshot, "price", "buy".equals(side) ? buyPrice : sellPrice);
        set(snapshot, "totalQuantity", totalQuantity);
        set(snapshot, "filledQuantity", filledQuantity);
        set(snapshot, "spentAmount", 0);
        set(snapshot, "status", status);
        set(snapshot, "offerId", offerId);
        set(snapshot, "startedAt", 100L);
        set(snapshot, "endedAt", "completed".equals(status) ? 200L : 0L);
        set(snapshot, "eventSequence", 1L);
        set(snapshot, "lastEventAt", 200L);
        set(snapshot, "suggestedBuyPrice", buyPrice);
        set(snapshot, "suggestedSellPrice", sellPrice);
        set(snapshot, "lowestSellPrice", lowestPrice);
        set(snapshot, "sourceBuyOfferId", sourceBuyOfferId);
        return snapshot;
    }

    private static Object serverState(int slot, int sellPrice) throws Exception
    {
        Object server = newNested("ServerSlotState");
        set(server, "slot_number", slot);
        set(server, "item_id", ITEM_ID);
        set(server, "item_name", "Sea turtle");
        set(server, "side", "buy");
        set(server, "price", 939);
        set(server, "total_quantity", 10);
        set(server, "filled_quantity", 10);
        set(server, "spent_amount", 0);
        set(server, "status", "completed");
        set(server, "runelite_offer_id", "buy-sea-turtle");
        set(server, "version", 1L);
        set(server, "event_sequence", 1L);
        set(server, "last_event_at", 200L);
        set(server, "suggested_buy_price", 939);
        set(server, "suggested_sell_price", sellPrice);
        set(server, "lowest_sell_price", 958);
        return server;
    }

    private static Object newNested(String simpleName) throws Exception
    {
        Class<?> type = Class.forName(
            OsrsFlipperSyncPlugin.class.getName() + "$" + simpleName);
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Object> snapshots(OsrsFlipperSyncPlugin plugin) throws Exception
    {
        return (Map<Integer, Object>) field(plugin, "slotSnapshots");
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, MarketPriceView> marketPrices(
        OsrsFlipperSyncPlugin plugin) throws Exception
    {
        return (Map<Integer, MarketPriceView>) field(plugin, "marketPrices");
    }

    @SuppressWarnings("unchecked")
    private static Deque<Object> outbox(OsrsFlipperSyncPlugin plugin) throws Exception
    {
        return (Deque<Object>) field(plugin, "outbox");
    }

    private static FlipCyclePlanBook cycles(OsrsFlipperSyncPlugin plugin) throws Exception
    {
        return (FlipCyclePlanBook) field(plugin, "flipCycles");
    }

    private static void overview(OsrsFlipperSyncPlugin plugin, int instantBuy) throws Exception
    {
        RuneliteOverviewView.Opportunity opportunity = new RuneliteOverviewView.Opportunity(
            ITEM_ID, "Sea turtle", "expected",
            939, 1_002, instantBuy, 990,
            10, 0, 10, 0, 0, 300, 958);
        set(plugin, "overview", new RuneliteOverviewView(
            Collections.singletonList(opportunity),
            Collections.emptyList(),
            null,
            null,
            null,
            300));
    }

    private static int intField(Object target, String name) throws Exception
    {
        return ((Number) field(target, name)).intValue();
    }

    private static long longField(Object target, String name) throws Exception
    {
        return ((Number) field(target, name)).longValue();
    }

    private static Object field(Object target, String name) throws Exception
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
