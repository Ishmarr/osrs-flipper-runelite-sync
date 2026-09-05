package com.osrsflipper.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Locale;

/** Validate the existing Worker contracts before acknowledging local commands. */
final class WorkerResponseValidation
{
    static boolean cash(String body)
    {
        try
        {
            JsonObject cash = successful(body).getAsJsonObject("cash");
            long available = integer(cash, "available");
            long reserved = integer(cash, "reserved");
            return reserved >= 0 && integer(cash, "updated_at") >= 0 &&
                Math.addExact(available, reserved) == integer(cash, "available_plus_reserved");
        }
        catch (RuntimeException exception) { return false; }
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
