package com.osrsflipper.sync;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;

/** Independent channels: a healthy Wiki/overview/heartbeat must not hide failed GE delivery. */
final class SyncHealthTracker
{
    enum Channel
    {
        OVERVIEW("Flips/statistieken"), EVENTS("GE-updates"), STATE("GE-controle"),
        STATUS("Verbindingscontrole"), HEARTBEAT("Heartbeat"), CONNECTION("Koppeling"), STORAGE("Lokale opslag");

        final String label;

        Channel(String label)
        {
            this.label = label;
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final Map<Channel, State> states = new EnumMap<>(Channel.class);

    void clear()
    {
        states.clear();
    }

    void fail(Channel channel, String safeReason, long now)
    {
        State state = states.computeIfAbsent(channel, ignored -> new State());
        state.reason = safeReason;
        state.failures = Math.min(10, state.failures + 1);
        state.retryAt = now + retryDelaySeconds(state.failures);
    }

    void succeed(Channel channel, long now)
    {
        State state = states.computeIfAbsent(channel, ignored -> new State());
        state.lastSuccess = now;
        state.reason = null;
        state.failures = 0;
        state.retryAt = 0;
    }

    boolean failed(Channel channel)
    {
        State state = states.get(channel);
        return state != null && state.reason != null;
    }

    long retryAt(Channel channel)
    {
        State state = states.get(channel);
        return state == null ? 0 : state.retryAt;
    }

    static long retryDelaySeconds(int failures)
    {
        return Math.min(300, 15L << Math.min(5, Math.max(0, failures - 1)));
    }

    String banner(int queuedEvents)
    {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Channel, State> entry : states.entrySet())
        {
            State state = entry.getValue();
            if (state.reason == null)
            {
                continue;
            }
            if (text.length() > 0)
            {
                text.append('\n');
            }
            text.append(entry.getKey().label).append(": ").append(state.reason)
                .append(". Laatst OK: ")
                .append(state.lastSuccess <= 0 ? "nog niet" : TIME.format(
                    Instant.ofEpochSecond(state.lastSuccess).atZone(ZoneId.systemDefault())));
        }
        if (text.length() > 0)
        {
            text.append(failed(Channel.CONNECTION)
                ? "\nGegevens mogelijk verouderd. Opnieuw koppelen via Sync."
                : "\nGegevens mogelijk verouderd. Automatisch herstel actief.")
                .append("\nGE-wachtrij: ").append(Math.max(0, queuedEvents));
        }
        return text.toString();
    }

    private static final class State
    {
        private String reason;
        private int failures;
        private long lastSuccess;
        private long retryAt;
    }
}
