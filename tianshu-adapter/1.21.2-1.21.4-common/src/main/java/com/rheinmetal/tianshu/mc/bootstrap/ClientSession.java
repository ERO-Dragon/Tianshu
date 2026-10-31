package com.rheinmetal.tianshu.mc.bootstrap;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Owns one fully assembled client session and releases it in reverse order.
 *
 * <p>One instance per loader session: whoever builds the assembly hands every acquired resource to {@link #own},
 * and closing the session releases them in the reverse order they were acquired. The loader adapter is the only
 * caller, so the type is public but its use is deliberately narrow.</p>
 */
public final class ClientSession implements AutoCloseable {
    private final Deque<CleanupEntry> cleanupActions = new ArrayDeque<>();
    private boolean closed;

    public synchronized Ownership own(Runnable cleanupAction) {
        if (closed) {
            throw new IllegalStateException("CLIENT_SESSION_CLOSED");
        }
        CleanupEntry entry = new CleanupEntry(Objects.requireNonNull(cleanupAction, "cleanupAction"));
        cleanupActions.push(entry);
        return new Ownership(this, entry);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        Throwable firstFailure = null;
        while (!cleanupActions.isEmpty()) {
            try {
                cleanupActions.pop().cleanupAction().run();
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        rethrow(firstFailure);
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private synchronized void release(CleanupEntry entry) {
        cleanupActions.remove(entry);
    }

    private record CleanupEntry(Runnable cleanupAction) {
    }

    public static final class Ownership {
        private ClientSession owner;
        private CleanupEntry entry;

        private Ownership(ClientSession owner, CleanupEntry entry) {
            this.owner = owner;
            this.entry = entry;
        }

        public synchronized void release() {
            ClientSession currentOwner = owner;
            CleanupEntry currentEntry = entry;
            if (currentOwner == null || currentEntry == null) {
                return;
            }
            owner = null;
            entry = null;
            currentOwner.release(currentEntry);
        }
    }
}
