package com.osrsflipper.sync;

import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

public class SyncStorageContextTest
{
    @Test
    public void equivalentOriginsOwnerCaseAndTokenRotationKeepTheSameContext()
    {
        SyncStorageContext original = context("https://EXAMPLE.test:443/path?query=1", " ALICE@EXAMPLE.TEST ", "device-a", "old", 42);
        SyncStorageContext rotated = context("https://example.test/other", "alice@example.test", "device-a", "new", 42);
        assertEquals(original.accountKey, rotated.accountKey);
        assertTrue(original.accountKey.matches("[0-9a-f]{64}"));
    }

    @Test
    public void everyAuthenticationAndAccountBoundaryHasItsOwnContext()
    {
        SyncStorageContext original = context("https://example.test", "alice@example.test", "device-a", "token", 42);
        assertNotEquals(original.accountKey, context("https://example.test:444", "alice@example.test", "device-a", "token", 42).accountKey);
        assertNotEquals(original.accountKey, context("https://example.test", "bob@example.test", "device-a", "token", 42).accountKey);
        assertNotEquals(original.accountKey, context("https://example.test", "alice@example.test", "device-b", "token", 42).accountKey);
        assertNotEquals(original.accountKey, context("https://example.test", "alice@example.test", "device-a", "token", 43).accountKey);
    }

    @Test
    public void ownerNormalizationDoesNotDependOnTheComputersLocale()
    {
        Locale previous = Locale.getDefault();
        try
        {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(context("https://example.test", "ALICE@example.test", "d", "t", 42).accountKey,
                context("https://example.test", "alice@example.test", "d", "t", 42).accountKey);
        }
        finally
        {
            Locale.setDefault(previous);
        }
    }

    private static SyncStorageContext context(String address, String owner, String device, String token, long account)
    {
        return SyncStorageContext.capture(new OsrsFlipperSyncConfig()
        {
            @Override public String webappAddress() { return address; }
            @Override public String ownerEmail() { return owner; }
            @Override public String deviceId() { return device; }
            @Override public String deviceToken() { return token; }
        }, account);
    }
}
