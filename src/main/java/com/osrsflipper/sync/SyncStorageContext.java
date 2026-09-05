package com.osrsflipper.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import okhttp3.HttpUrl;

/** Storage identity deliberately excludes the rotatable bearer token. */
final class SyncStorageContext
{
    final String connectionKey;
    final String accountKey;

    private SyncStorageContext(String connectionKey, long accountHash)
    {
        this.connectionKey = connectionKey;
        this.accountKey = digest(connectionKey + ":" + Long.toUnsignedString(accountHash));
    }

    static SyncStorageContext capture(OsrsFlipperSyncConfig config, long accountHash)
    {
        String address = clean(config.webappAddress());
        HttpUrl url = HttpUrl.parse(address);
        String origin = url == null ? address : url.scheme() + "://" + url.host() + ":" + url.port();
        String owner = clean(config.ownerEmail()).toLowerCase(Locale.ROOT);
        String device = clean(config.deviceId());
        // Length prefixes avoid ambiguity even with unexpected input characters.
        String connection = part(origin) + part(owner) + part(device);
        return new SyncStorageContext(digest(connection), accountHash);
    }

    private static String part(String value)
    {
        return value.length() + ":" + value;
    }

    private static String clean(String value)
    {
        return value == null ? "" : value.trim();
    }

    private static String digest(String value)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte part : digest)
            {
                result.append(Character.forDigit((part >>> 4) & 15, 16));
                result.append(Character.forDigit(part & 15, 16));
            }
            return result.toString();
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
        }
    }
}
