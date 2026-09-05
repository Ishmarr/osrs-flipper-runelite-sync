package com.osrsflipper.sync;

import java.util.UUID;

/** One user cash command; its identity survives transport retries and persistence. */
final class PendingCashUpdate
{
    final String requestId;
    final long balance;

    private PendingCashUpdate(String requestId, long balance)
    {
        this.requestId = requestId;
        this.balance = balance;
    }

    static PendingCashUpdate create(long balance)
    {
        return new PendingCashUpdate(UUID.randomUUID().toString(), Math.max(0, balance));
    }

    boolean isValid()
    {
        return balance >= 0 && requestId != null &&
            requestId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }

    boolean hasSameIdentity(PendingCashUpdate other)
    {
        return other != null && requestId != null && requestId.equals(other.requestId);
    }
}
