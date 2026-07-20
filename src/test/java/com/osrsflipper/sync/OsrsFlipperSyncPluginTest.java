package com.osrsflipper.sync;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class OsrsFlipperSyncPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(OsrsFlipperSyncPlugin.class);
        RuneLite.main(args);
    }
}
