package com.osrsflipper.sync;

import java.util.Locale;
import java.util.Objects;
import okhttp3.HttpUrl;

/** A bearer token is valid only for the profile, origin and identity that paired it. */
final class PairingCredentials
{
    String profile;
    String origin;
    String owner;
    String device;
    String token;

    static PairingCredentials create(String profile, HttpUrl endpoint, String owner, String device, String token)
    {
        PairingCredentials result = new PairingCredentials();
        result.profile = profile;
        result.origin = origin(endpoint);
        result.owner = clean(owner).toLowerCase(Locale.ROOT);
        result.device = clean(device);
        result.token = clean(token);
        if (!result.isValid()) throw new IllegalArgumentException("Invalid pairing credentials");
        return result;
    }

    boolean isValid()
    {
        return profile != null && profile.matches("default|[0-9]{1,20}") && origin != null && origin.length() <= 2048 &&
            origin.equals(origin(HttpUrl.parse(origin))) && owner != null && !owner.isEmpty() && owner.length() <= 254 &&
            device != null && !device.isEmpty() && device.length() <= 128 &&
            token != null && token.matches("rlt_[A-Za-z0-9_-]{40,120}");
    }

    boolean matches(String profile, HttpUrl endpoint, String owner, String device)
    {
        return isValid() && Objects.equals(this.profile, profile) &&
            Objects.equals(origin, origin(endpoint)) &&
            this.owner.equals(clean(owner).toLowerCase(Locale.ROOT)) && this.device.equals(clean(device));
    }

    static String origin(HttpUrl endpoint)
    {
        if (endpoint == null || !"https".equals(endpoint.scheme()) ||
            !endpoint.username().isEmpty() || !endpoint.password().isEmpty()) return null;
        return endpoint.newBuilder().encodedPath("/").query(null).fragment(null).build().toString();
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
