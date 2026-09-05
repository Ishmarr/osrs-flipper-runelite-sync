package com.osrsflipper.sync;

import java.util.Objects;

/**
 * Owns the single in-flight request to the OSRS Flip Tracker Worker.
 *
 * <p>The Wiki price API deliberately does not use this coordinator. A request
 * token is normally the corresponding OkHttp {@code Call}; accepting an
 * opaque token keeps the scheduling rules independently testable.</p>
 */
final class WorkerRequestCoordinator
{
    enum Kind
    {
        PAIRING,
        STATUS,
        HEARTBEAT,
        EVENT,
        SNAPSHOT,
        STATE,
        OVERVIEW,
        CASH
    }

    enum Cancellation
    {
        NONE,
        OVERVIEW_PREEMPTED,
        CONTEXT_CHANGED,
        SHUTDOWN
    }

    enum CompletionStatus
    {
        CURRENT,
        LOCALLY_CANCELLED,
        STALE
    }

    static final class Completion
    {
        final CompletionStatus status;
        final Cancellation cancellation;

        private Completion(CompletionStatus status, Cancellation cancellation)
        {
            this.status = status;
            this.cancellation = cancellation;
        }

        boolean shouldHandleResponse()
        {
            return status == CompletionStatus.CURRENT;
        }
    }

    private Active active;

    synchronized boolean begin(Kind kind, Object token, Runnable cancel)
    {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(cancel, "cancel");
        if (active != null)
        {
            return false;
        }
        active = new Active(kind, token, cancel);
        return true;
    }

    synchronized boolean isActive()
    {
        return active != null;
    }

    synchronized Kind activeKind()
    {
        return active == null ? null : active.kind;
    }

    boolean cancelOverview(Cancellation cancellation)
    {
        Runnable cancel = markCancelled(Kind.OVERVIEW, cancellation);
        if (cancel == null)
        {
            return false;
        }
        cancel.run();
        return true;
    }

    boolean cancelActive(Cancellation cancellation)
    {
        Runnable cancel;
        synchronized (this)
        {
            if (active == null)
            {
                return false;
            }
            if (active.cancellation != Cancellation.NONE)
            {
                return true;
            }
            active.cancellation = requireLocalCancellation(cancellation);
            cancel = active.cancel;
        }
        cancel.run();
        return true;
    }

    synchronized Completion complete(Kind kind, Object token)
    {
        if (active == null || active.kind != kind || active.token != token)
        {
            return new Completion(CompletionStatus.STALE, Cancellation.NONE);
        }
        Cancellation cancellation = active.cancellation;
        active = null;
        return new Completion(
            cancellation == Cancellation.NONE
                ? CompletionStatus.CURRENT
                : CompletionStatus.LOCALLY_CANCELLED,
            cancellation);
    }

    private synchronized Runnable markCancelled(Kind expectedKind, Cancellation cancellation)
    {
        if (active == null || active.kind != expectedKind)
        {
            return null;
        }
        if (active.cancellation != Cancellation.NONE)
        {
            return () -> {};
        }
        active.cancellation = requireLocalCancellation(cancellation);
        return active.cancel;
    }

    private static Cancellation requireLocalCancellation(Cancellation cancellation)
    {
        if (cancellation == null || cancellation == Cancellation.NONE)
        {
            throw new IllegalArgumentException("A local cancellation reason is required");
        }
        return cancellation;
    }

    private static final class Active
    {
        private final Kind kind;
        private final Object token;
        private final Runnable cancel;
        private Cancellation cancellation = Cancellation.NONE;

        private Active(Kind kind, Object token, Runnable cancel)
        {
            this.kind = kind;
            this.token = token;
            this.cancel = cancel;
        }
    }
}
