package com.osrsflipper.sync;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(OsrsFlipperSyncConfig.GROUP)
public interface OsrsFlipperSyncConfig extends Config
{
    String GROUP = "osrsflippersync";

    @ConfigItem(
        keyName = "webappAddress",
        name = "Webapp-adres",
        description = "HTTPS-adres van de OSRS Flip Tracker-webapp.",
        position = 0
    )
    default String webappAddress()
    {
        return "https://osrs-flip-tracker.steffnys.workers.dev";
    }

    @ConfigItem(
        keyName = "connectionStatus",
        name = "Verbindingsstatus",
        description = "Wordt automatisch door de plugin bijgewerkt. De actieknoppen staan in het OSRS Flipper-zijpaneel.",
        position = 1
    )
    default String connectionStatus()
    {
        return "Nog niet gekoppeld";
    }

    @ConfigItem(
        keyName = "debugLogging",
        name = "Uitgebreide logging",
        description = "Schrijf extra synchronisatiedetails naar de RuneLite-log. Laat dit normaal uitgeschakeld.",
        position = 2
    )
    default boolean debugLogging()
    {
        return false;
    }

    @ConfigItem(
        keyName = "deviceToken",
        name = "Device token",
        description = "Interne geheime apparaattoken die pas na koppeling door de webapp wordt uitgegeven.",
        hidden = true,
        secret = true,
        position = 100
    )
    default String deviceToken()
    {
        return "";
    }

    @ConfigItem(
        keyName = "deviceId",
        name = "Device ID",
        description = "Intern apparaat-ID.",
        hidden = true,
        position = 101
    )
    default String deviceId()
    {
        return "";
    }

    @ConfigItem(
        keyName = "ownerEmail",
        name = "Gekoppelde gebruiker",
        description = "Webappgebruiker waaraan dit apparaat gekoppeld is.",
        hidden = true,
        position = 102
    )
    default String ownerEmail()
    {
        return "";
    }

    @ConfigItem(
        keyName = "linkedAt",
        name = "Gekoppeld op",
        description = "Intern koppeltijdstip.",
        hidden = true,
        position = 103
    )
    default String linkedAt()
    {
        return "";
    }
}
