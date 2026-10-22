package com.rheinmetal.tianshu.neoforge.bootstrap;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Owns one fully assembled NeoForge client session and releases it in reverse order. */
final class NeoForgeClientSession implements AutoCloseable {
    private final Deque<CleanupEntry> cleanupActions = new ArrayDeque<>();
    private boolean closed;

    synchronized Ownership own(Runnable cleanupAction) {
        if (closed) {
            throw new IllegalStateException("NEOFORGE_CLIENT_SESSION_CLOSED");
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

    static final class Ownership {
        private NeoForgeClientSession owner;
        private CleanupEntry entry;

        private Ownership(NeoForgeClientSession owner, CleanupEntry entry) {
            this.owner = owner;
            this.entry = entry;
        }

        synchronized void release() {
            NeoForgeClientSession currentOwner = owner;
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
