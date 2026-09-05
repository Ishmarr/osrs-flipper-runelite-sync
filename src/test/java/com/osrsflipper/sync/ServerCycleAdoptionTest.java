package com.osrsflipper.sync;

import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ServerCycleAdoptionTest
{
    private static final int ITEM_ID = 3004;
    private static final Gson GSON = new Gson();

    @Test
    public void adoptedBuyCountsOnlyTheNewFillAndKeepsSubmittedPayloadsUnchanged() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        Object buy = snapshot("local-buy", "buy", "local-buy", 100, 40, "partially_filled");
        snapshots(plugin).put(1, buy);
        record(plugin, buy);
        cycles(plugin).recordSell("local-buy", "linked-sale", 2, ITEM_ID, "Test item",
            1_000, 1_100, 1_020, 20, 5, "partially_filled", 200, 210);

        Object event = invoke(buy, "toSyncEvent", new Class<?>[]{String.class}, "partial_buy");
        Object queued = nested("QueuedEvent");
        set(queued, "event", event);
        outbox(plugin).addLast(queued);
        Object pending = nested("PendingSnapshot");
        set(pending, "snapshotId", "already-submitted");
        set(pending, "slots", Collections.singletonList(
            invoke(buy, "toSnapshotMap", new Class<?>[]{long.class}, 200L)));
        set(plugin, "pendingSnapshot", pending);
        String queuedBefore = GSON.toJson(queued);
        String pendingBefore = GSON.toJson(pending);

        Object server = server("server-buy", 900, 918);
        adopt(plugin, buy, server);
        adopt(plugin, buy, server);
        set(buy, "filledQuantity", 100);
        set(buy, "status", "completed");
        record(plugin, buy);

        FlipCyclePlanBook.Cycle cycle = cycles(plugin).cycle("local-buy");
        assertEquals("server-buy", get(buy, "offerId"));
        assertEquals("local-buy", get(buy, "sourceBuyOfferId"));
        assertEquals(Collections.singletonMap("server-buy", 100), cycle.buyFills);
        assertEquals(100, cycle.acquiredQuantity());
        assertEquals(5, cycle.soldQuantity());
        assertEquals(15, cycle.reservedQuantity());
        assertEquals(80, cycle.availableQuantity());
        assertEquals(queuedBefore, GSON.toJson(queued));
        assertEquals(pendingBefore, GSON.toJson(pending));
        assertEquals(1, outbox(plugin).size());
    }

    @Test
    public void legacyImplicitCycleLinkSurvivesAdoptionCollectAndRestart() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        Object buy = snapshot("local-buy", "buy", "local-buy", 100, 40, "partially_filled");
        record(plugin, buy);
        set(buy, "sourceBuyOfferId", "");
        snapshots(plugin).put(1, buy);
        adopt(plugin, buy, server("server-buy", 900, 918));
        assertEquals("local-buy", get(buy, "sourceBuyOfferId"));

        OsrsFlipperSyncPlugin restarted = new OsrsFlipperSyncPlugin();
        cycles(restarted).restore(GSON.fromJson(
            GSON.toJson(cycles(plugin).persistedCycles()), FlipCyclePlanBook.Cycle[].class));
        Object restoredBuy = GSON.fromJson(GSON.toJson(buy), buy.getClass());
        snapshots(restarted).put(1, restoredBuy);
        invoke(restarted, "recoverFlipCyclesFromSlots", new Class<?>[0]);
        adopt(restarted, restoredBuy, server("server-buy", 900, 918));
        set(restoredBuy, "filledQuantity", 100);
        set(restoredBuy, "status", "completed");
        record(restarted, restoredBuy);
        set(restoredBuy, "status", "empty");
        record(restarted, restoredBuy);
        invoke(restarted, "recoverFlipCyclesFromSlots", new Class<?>[0]);

        assertEquals(1, cycles(restarted).size());
        assertNull(cycles(restarted).cycle("server-buy"));
        FlipCyclePlanBook.Cycle cycle = cycles(restarted).selectForSetup(ITEM_ID);
        assertNotNull(cycle);
        assertEquals(100, cycle.availableQuantity());
        assertEquals(900, cycle.frozenBuyPrice);
        assertEquals(918, cycle.lowestSellPrice);
    }

    @Test
    public void serverFloorReachesLinkedSlotsAndTheNextSellSetup() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        Object buy = snapshot("same-buy", "buy", "same-buy", 100, 100, "completed");
        Object sell = snapshot("existing-sell", "sell", "same-buy", 20, 5, "partially_filled");
        snapshots(plugin).put(1, buy);
        snapshots(plugin).put(2, sell);
        record(plugin, buy);
        record(plugin, sell);

        adopt(plugin, buy, server("same-buy", 900, 918));
        assertEquals(900, get(sell, "suggestedBuyPrice"));
        assertEquals(918, get(sell, "lowestSellPrice"));
        set(buy, "status", "empty");
        record(plugin, buy);
        FlipperOfferView setup = (FlipperOfferView) invoke(plugin, "openCycleOffer",
            new Class<?>[]{int.class, String.class}, ITEM_ID, "sell");

        assertNotNull(setup);
        assertEquals(900, setup.suggestedBuyPrice);
        assertEquals(918, setup.lowestSellPrice);
        assertEquals(80, setup.totalQuantity);
        assertEquals(1_100, setup.suggestedSellPrice);
    }

    @Test
    public void adoptedSellRetainsItsReservationWithoutCountingOldFillsTwice() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        Object buy = snapshot("buy", "buy", "buy", 100, 100, "completed");
        Object sell = snapshot("local-sell", "sell", "buy", 60, 20, "partially_filled");
        record(plugin, buy);
        record(plugin, sell);
        adopt(plugin, sell, server("server-sell", 1_000, 1_020));
        adopt(plugin, sell, server("server-sell", 1_000, 1_020));
        set(sell, "filledQuantity", 30);
        record(plugin, sell);

        FlipCyclePlanBook.Cycle cycle = cycles(plugin).cycle("buy");
        assertEquals(1, cycle.sales.size());
        assertFalse(cycle.sales.containsKey("local-sell"));
        assertEquals(30, cycle.soldQuantity());
        assertEquals(30, cycle.reservedQuantity());
        assertEquals(40, cycle.availableQuantity());
        set(sell, "status", "cancelled");
        record(plugin, sell);
        set(sell, "status", "empty");
        record(plugin, sell);
        assertEquals(70, cycles(plugin).cycle("buy").availableQuantity());
    }

    @Test
    public void aliasesMergeByMaximumAndOtherBuyOffersRemainDistinct()
    {
        FlipCyclePlanBook book = new FlipCyclePlanBook();
        book.recordBuy("root", "older-purchase", 1, ITEM_ID, "Test item",
            1_000, 1_100, 1_020, 25, 25, "completed", 100, 100);
        book.recordBuy("root", "local-reprice", 1, ITEM_ID, "Test item",
            1_000, 1_100, 1_020, 75, 40, "partially_filled", 100, 200);
        book.recordBuy("root", "server-reprice", 1, ITEM_ID, "Test item",
            1_000, 1_100, 1_020, 75, 60, "partially_filled", 100, 210);

        book.adoptOfferIdentity("root", ITEM_ID, "buy", "local-reprice", "server-reprice");
        book.adoptOfferIdentity("root", ITEM_ID, "buy", "local-reprice", "server-reprice");

        FlipCyclePlanBook.Cycle cycle = book.cycle("root");
        assertEquals(85, cycle.acquiredQuantity());
        assertEquals(2, cycle.buyFills.size());
        assertEquals(Integer.valueOf(25), cycle.buyFills.get("older-purchase"));
        assertEquals(Integer.valueOf(60), cycle.buyFills.get("server-reprice"));
    }

    @Test
    public void duplicateSellAliasesKeepTheLatestAllocationState()
    {
        FlipCyclePlanBook book = new FlipCyclePlanBook();
        book.recordBuy("buy", "buy", 1, ITEM_ID, "Test item",
            1_000, 1_100, 1_020, 100, 100, "completed", 100, 100);
        book.recordSell("buy", "local-sell", 2, ITEM_ID, "Test item",
            1_000, 1_100, 1_020, 60, 20, "partially_filled", 200, 200);
        book.recordSell("buy", "server-sell", 2, ITEM_ID, "Test item",
            1_000, 1_100, 1_020, 60, 30, "cancelled", 200, 210);

        book.adoptOfferIdentity("buy", ITEM_ID, "sell", "local-sell", "server-sell");

        FlipCyclePlanBook.Cycle cycle = book.cycle("buy");
        assertEquals(30, cycle.soldQuantity());
        assertEquals(0, cycle.reservedQuantity());
        assertEquals(70, cycle.availableQuantity());
        assertEquals(1, cycle.sales.size());
    }

    private static Object snapshot(String offerId, String side, String source,
        int total, int filled, String status) throws Exception
    {
        Object snapshot = nested("SlotSnapshot");
        set(snapshot, "slotNumber", "buy".equals(side) ? 1 : 2);
        set(snapshot, "itemId", ITEM_ID);
        set(snapshot, "itemName", "Test item");
        set(snapshot, "side", side);
        set(snapshot, "price", "buy".equals(side) ? 1_000 : 1_100);
        set(snapshot, "totalQuantity", total);
        set(snapshot, "filledQuantity", filled);
        set(snapshot, "status", status);
        set(snapshot, "offerId", offerId);
        set(snapshot, "sourceBuyOfferId", source);
        set(snapshot, "startedAt", 100L);
        set(snapshot, "eventSequence", 1L);
        set(snapshot, "lastEventAt", 200L);
        set(snapshot, "suggestedBuyPrice", 1_000);
        set(snapshot, "suggestedSellPrice", 1_100);
        set(snapshot, "lowestSellPrice", 1_020);
        return snapshot;
    }

    private static Object server(String offerId, int buyPrice, int lowestSellPrice) throws Exception
    {
        Object server = nested("ServerSlotState");
        set(server, "runelite_offer_id", offerId);
        set(server, "version", 1L);
        set(server, "suggested_buy_price", buyPrice);
        set(server, "lowest_sell_price", lowestSellPrice);
        return server;
    }

    private static void adopt(OsrsFlipperSyncPlugin plugin, Object local, Object server) throws Exception
    {
        invoke(plugin, "adoptServerMetadata", new Class<?>[]{int.class, local.getClass(), server.getClass()},
            get(local, "slotNumber"), local, server);
    }

    private static void record(OsrsFlipperSyncPlugin plugin, Object snapshot) throws Exception
    {
        invoke(plugin, "recordFlipCycle", new Class<?>[]{snapshot.getClass()}, snapshot);
    }

    private static FlipCyclePlanBook cycles(OsrsFlipperSyncPlugin plugin) throws Exception
    {
        return (FlipCyclePlanBook) get(plugin, "flipCycles");
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Object> snapshots(OsrsFlipperSyncPlugin plugin) throws Exception
    {
        return (Map<Integer, Object>) get(plugin, "slotSnapshots");
    }

    @SuppressWarnings("unchecked")
    private static Deque<Object> outbox(OsrsFlipperSyncPlugin plugin) throws Exception
    {
        return (Deque<Object>) get(plugin, "outbox");
    }

    private static Object nested(String name) throws Exception
    {
        Constructor<?> constructor = Class.forName(OsrsFlipperSyncPlugin.class.getName() + "$" + name)
            .getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments)
        throws Exception
    {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, arguments);
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
