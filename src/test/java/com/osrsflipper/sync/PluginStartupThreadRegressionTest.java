package com.osrsflipper.sync;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PluginStartupThreadRegressionTest
{
    @Test
    public void emptyStartupSelectionDoesNotResolveItemMetadata() throws Exception
    {
        OsrsFlipperSyncPlugin plugin = new OsrsFlipperSyncPlugin();
        Method resolve = OsrsFlipperSyncPlugin.class.getDeclaredMethod(
            "resolveSelectedGeOpportunity",
            int.class,
            String.class);
        resolve.setAccessible(true);

        SelectedGeOpportunityResolver.Resolution result =
            (SelectedGeOpportunityResolver.Resolution) resolve.invoke(plugin, 0, "");

        assertNull(result.opportunity);
    }

    @Test
    public void sidePanelResolutionOnlyUsesTheClientThreadNameCache() throws Exception
    {
        String source = new String(Files.readAllBytes(Paths.get(
            "src/main/java/com/osrsflipper/sync/OsrsFlipperSyncPlugin.java")), StandardCharsets.UTF_8);
        int start = source.indexOf(
            "private SelectedGeOpportunityResolver.Resolution resolveSelectedGeOpportunity(");
        int end = source.indexOf("private FlipperOfferView exactSelectedOffer(", start);

        assertTrue("resolveSelectedGeOpportunity ontbreekt", start >= 0 && end > start);
        String method = source.substring(start, end);
        assertTrue("de client-threadcache wordt niet gebruikt", method.contains("focusedGeItemName"));
        assertFalse("zijpaneelresolutie leest opnieuw RuneLite-itemdefinities",
            method.contains("itemName(itemId)"));
    }
}
