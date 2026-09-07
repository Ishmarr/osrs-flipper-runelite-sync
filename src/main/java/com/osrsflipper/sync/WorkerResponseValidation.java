package com.osrsflipper.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Locale;

/** Validate the existing Worker contracts before acknowledging local commands. */
final class WorkerResponseValidation
{
    static long snapshotContinuationDelaySeconds(String body, String snapshotId)
    {
        try
        {
            JsonObject root = new JsonParser().parse(body).getAsJsonObject();
            if (!explicitFalse(root, "success") || !bool(root, "retryable") ||
                !explicitFalse(root, "reconcile_required") ||
                !"snapshot_processing".equals(string(root, "code"))) return 0;
            // Legacy chunk responses may omit the ID. Their HTTP callback is
            // already bound to the same immutable snapshot and account context.
            if (root.has("snapshot_id") && !string(root, "snapshot_id").equals(snapshotId)) return 0;
            if (root.has("snapshot") &&
                !string(root.getAsJsonObject("snapshot"), "snapshot_id").equals(snapshotId)) return 0;
            if (!root.has("retry_after_ms")) return 1;
            long milliseconds = integer(root, "retry_after_ms");
            return milliseconds > 0 && milliseconds <= 30_000 ? Math.max(1, (milliseconds + 999) / 1000) : 0;
        }
        catch (RuntimeException exception) { return 0; }
    }

    private static boolean explicitFalse(JsonObject root, String name)
    {
        JsonElement value = root.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() &&
            !value.getAsBoolean();
    }

    static long eventContinuationDelaySeconds(String body, String eventId)
    {
        try
        {
            JsonObject root = new JsonParser().parse(body).getAsJsonObject();
            JsonElement success = root.get("success");
            if (success == null || !success.isJsonPrimitive() || !success.getAsJsonPrimitive().isBoolean() ||
                success.getAsBoolean() || !bool(root, "retryable") ||
                !"event_processing".equals(string(root, "code")) ||
                eventId == null || !eventId.equals(string(root, "event_id"))) return 0;
            long milliseconds = integer(root, "retry_after_ms");
            return milliseconds > 0 && milliseconds <= 30_000 ? Math.max(1, (milliseconds + 999) / 1000) : 0;
        }
        catch (RuntimeException exception) { return 0; }
    }

    static boolean cash(String body)
    {
        return cashBalance(body) != null;
    }

    static RuneliteOverviewView.CashBalance cashBalance(String body)
    {
        try { return cashBalance(successful(body).getAsJsonObject("cash")); }
        catch (RuntimeException exception) { return null; }
    }

    static RuneliteOverviewView.CashBalance cashBalance(JsonObject cash)
    {
        try
        {
            long available = integer(cash, "available");
            long reserved = integer(cash, "reserved");
            long updatedAt = integer(cash, "updated_at");
            long total = integer(cash, "available_plus_reserved");
            long version = cash.has("version") ? integer(cash, "version") : -1;
            long snapshotAtMs = cash.has("snapshot_at_ms") ? integer(cash, "snapshot_at_ms") : 0;
            if (reserved < 0 || updatedAt < 0 || snapshotAtMs < 0 ||
                (cash.has("version") && version < 0) || Math.addExact(available, reserved) != total)
                return null;
            // Reconciliation may legitimately report negative free cash. Keep
            // that signed balance instead of silently displaying an invented zero.
            return new RuneliteOverviewView.CashBalance(available, reserved, total,
                updatedAt, version, snapshotAtMs);
        }
        catch (RuntimeException exception) { return null; }
    }

    static boolean status(String body, String deviceId, String ownerEmail)
    {
        try
        {
            JsonObject root = successful(body);
            JsonObject device = root.getAsJsonObject("device");
            return integer(root, "server_time") > 0 && bool(device, "active") &&
                "active".equals(string(device, "status")) &&
                string(device, "device_id").equals(deviceId) &&
                sameOwner(string(root.getAsJsonObject("owner"), "email"), ownerEmail);
        }
        catch (RuntimeException exception) { return false; }
    }

    static boolean heartbeat(String body, String deviceId, String ownerEmail)
    {
        try
        {
            JsonObject root = successful(body);
            return integer(root, "heartbeat_at") > 0 && "active".equals(string(root, "status")) &&
                string(root, "device_id").equals(deviceId) &&
                sameOwner(string(root, "owner_email"), ownerEmail);
        }
        catch (RuntimeException exception) { return false; }
    }

    private static boolean sameOwner(String actual, String expected)
    {
        return expected != null && !actual.isEmpty() &&
            actual.trim().toLowerCase(Locale.ROOT).equals(expected.trim().toLowerCase(Locale.ROOT));
    }

    private static JsonObject successful(String body)
    {
        JsonObject root = new JsonParser().parse(body).getAsJsonObject();
        if (!bool(root, "success")) throw new IllegalArgumentException("Missing success acknowledgement");
        return root;
    }

    private static boolean bool(JsonObject object, String key)
    {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    private static String string(JsonObject object, String key)
    {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Missing string field");
        return value.getAsString();
    }

    private static long integer(JsonObject object, String key)
    {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() ||
            !value.getAsString().matches("-?[0-9]+")) throw new IllegalArgumentException("Missing integer field");
        long result = Long.parseLong(value.getAsString());
        if (result < -9_007_199_254_740_991L || result > 9_007_199_254_740_991L)
            throw new IllegalArgumentException("Unsafe Worker integer");
        return result;
    }
}
