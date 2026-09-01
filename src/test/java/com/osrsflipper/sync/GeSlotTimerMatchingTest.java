package com.osrsflipper.sync;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class GeSlotTimerMatchingTest
{
    @Test
    public void acceptsOnlyTheExactLiveOfferInstance() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = pluginWithSnapshot(
            "buy", "active", 4151, 2_000, 100, 1_000, 0);

        GeSlotTimerView matching = plugin.geSlotTimerView(
            0,
            offer(GrandExchangeOfferState.BUYING, 4151, 2_000, 100, 25));

        assertNotNull(matching);
        assertEquals("buy", matching.getSide());
        assertNull(plugin.geSlotTimerView(
            0,
            offer(GrandExchangeOfferState.BUYING, 4151, 2_001, 100, 25)));
        assertNull(plugin.geSlotTimerView(
            1,
            offer(GrandExchangeOfferState.BUYING, 4151, 2_000, 100, 25)));
    }

    @Test
    public void completedTimerRequiresTheLiveOfferToBeTerminalToo() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = pluginWithSnapshot(
            "buy", "completed", 4151, 2_000, 100, 1_000, 1_300);

        assertNull(plugin.geSlotTimerView(
            0,
            offer(GrandExchangeOfferState.BUYING, 4151, 2_000, 100, 100)));
        GeSlotTimerView completed = plugin.geSlotTimerView(
            0,
            offer(GrandExchangeOfferState.BOUGHT, 4151, 2_000, 100, 100));
        assertNotNull(completed);
        assertEquals("00:05:00", completed.timerText(9_999));
    }

    @SuppressWarnings("unchecked")
    private static OsrsFlipperSyncPlugin pluginWithSnapshot(
        String side,
        String status,
        int itemId,
        int price,
        int totalQuantity,
        long startedAt,
        long endedAt) throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        Class<?> snapshotType = Class.forName(
            "com.osrsflipper.sync.OsrsFlipperSyncPlugin$SlotSnapshot");
        Constructor<?> constructor = snapshotType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object snapshot = constructor.newInstance();
        set(snapshot, "slotNumber", 1);
        set(snapshot, "itemId", itemId);
        set(snapshot, "side", side);
        set(snapshot, "status", status);
        set(snapshot, "price", price);
        set(snapshot, "totalQuantity", totalQuantity);
        set(snapshot, "offerId", "timer-offer");
        set(snapshot, "startedAt", startedAt);
        set(snapshot, "endedAt", endedAt);

        Field snapshotsField = OsrsFlipperSyncPlugin.class.getDeclaredField("slotSnapshots");
        snapshotsField.setAccessible(true);
        ((Map<Integer, Object>) snapshotsField.get(plugin)).put(1, snapshot);
        return plugin;
    }

    private static void set(Object target, String fieldName, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static GrandExchangeOffer offer(
        GrandExchangeOfferState state,
        int itemId,
        int price,
        int totalQuantity,
        int quantitySold)
    {
        return new GrandExchangeOffer()
        {
            @Override
            public int getQuantitySold()
            {
                return quantitySold;
            }

            @Override
            public int getItemId()
            {
                return itemId;
            }

            @Override
            public int getTotalQuantity()
            {
                return totalQuantity;
            }

            @Override
            public int getPrice()
            {
                return price;
            }

            @Override
            public int getSpent()
            {
                return price * quantitySold;
            }

            @Override
            public GrandExchangeOfferState getState()
            {
                return state;
            }
        };
    }
}
