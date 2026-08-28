package com.osrsflipper.sync;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Deque;
import okhttp3.Request;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class WikiMarketPriceRequestTest
{
    @Test
    public void livePriceRequestRevalidatesTheSharedHttpCache()
    {
        Request request = OsrsFlipperSyncPlugin.wikiMarketPriceRequest(12_780);

        assertEquals("12780", request.url().queryParameter("id"));
        assertEquals("no-cache", request.header("Cache-Control"));
    }

    @Test
    public void invalidItemHasNoRequest()
    {
        assertNull(OsrsFlipperSyncPlugin.wikiMarketPriceRequest(0));
    }

    @Test
    public void periodicRefreshQueuesTheFocusedItemWithoutAnOfferSlot() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        Field focusedItem = OsrsFlipperSyncPlugin.class.getDeclaredField("focusedGeItemId");
        focusedItem.setAccessible(true);
        focusedItem.setInt(plugin, 12_780);

        Method refresh = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "requestMarketPrices",
            boolean.class);
        refresh.setAccessible(true);
        refresh.invoke(plugin, false);
        refresh.invoke(plugin, false);

        Field queueField = OsrsFlipperSyncPlugin.class.getDeclaredField("marketPriceQueue");
        queueField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Deque<Integer> queue = (Deque<Integer>) queueField.get(plugin);
        assertEquals(1, queue.size());
        assertEquals(Integer.valueOf(12_780), queue.peekFirst());
    }
}
