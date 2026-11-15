package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.tool.CancellationSignal;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Per-body operation state: at most one long world-writing operation per body, plus the cancellation signal of the
 * call that owns it.
 *
 * <p>Long operations (walking, mining, held use, collect, follow, sequence) acquire before touching the world and
 * release when their future finishes. Adapters poll {@link #cancelled(String)} inside their tick loops, which is how
 * a client-side cancel or timeout reaches a server-side loop.</p>
 *
 * <p>A lock older than five minutes is treated as leaked and reclaimed: futures can be dropped without completing
 * when a connection dies, and a body must not stay permanently busy.</p>
 *
 * <p>Ownership is a generation, not just a key. Taking the slot and deciding it was stale used to be a check followed
 * by a plain {@code put}, so two threads could both conclude "stale" and both take it — the exact parallel operation
 * this class exists to prevent — and the loser's cleanup could then erase the winner's cancellation signal or its
 * slot. Each grant now carries a stamp: the decide-and-take is one {@code compute}, a signal is only consulted while
 * its own generation holds the slot, and a release only clears what it owns.</p>
 */
public final class BodyOperations {

    private static final long STALE_NANOS = TimeUnit.MINUTES.toNanos(5);

    /** The stamp of the generation that currently holds each body's slot. */
    private static final Map<String, Long> ACTIVE = new ConcurrentHashMap<>();
    /** The cancellation signal bound by the generation named in {@link Bound#stamp()}. */
    private static final Map<String, Bound> SIGNALS = new ConcurrentHashMap<>();

    private record Bound(long stamp, CancellationSignal signal) {
    }

    private BodyOperations() {
    }

    /** Takes the body's operation slot; a second concurrent long operation is refused with {@code BUSY}. */
    public static Optional<ToolException> acquire(String actorId) {
        return acquire(actorId, null);
    }

    /**
     * @param stampOut optional one-element array that receives the grant's stamp, so the caller can release exactly
     *                 its own generation
     */
    private static Optional<ToolException> acquire(String actorId, long[] stampOut) {
        if (actorId == null || actorId.isBlank()) {
            return Optional.empty();
        }
        long grant = System.nanoTime();
        long now = grant;
        boolean[] taken = new boolean[1];
        // One step: look at the current stamp, decide whether it is leaked, and install ours if it is free or leaked.
        ACTIVE.compute(actorId, (key, previous) -> {
            if (previous == null || now - previous > STALE_NANOS) {
                taken[0] = true;
                return grant;
            }
            taken[0] = false;
            return previous;
        });
        if (!taken[0]) {
            return Optional.of(new ToolException(ToolError.BUSY,
                    "this body is already running an operation"));
        }
        if (stampOut != null && stampOut.length > 0) {
            stampOut[0] = grant;
        }
        return Optional.empty();
    }

    /** Releases whatever holds the slot. For cleanup and tests; a running operation should release its own stamp. */
    public static void release(String actorId) {
        if (actorId == null) {
            return;
        }
        ACTIVE.remove(actorId);
        SIGNALS.remove(actorId);
    }

    /** Releases only if the slot still belongs to this grant, and only the signal this grant bound. */
    private static void releaseOwned(String actorId, long stamp) {
        if (actorId == null || !ACTIVE.remove(actorId, stamp)) {
            return;
        }
        Bound bound = SIGNALS.get(actorId);
        if (bound != null && bound.stamp() == stamp) {
            SIGNALS.remove(actorId, bound);
        }
    }

    /**
     * Binds the current generation's cancellation signal; ignored when the body holds no slot, because a signal
     * without a grant belongs to nobody and must not stop the next operation.
     */
    public static void bindCancellation(String actorId, CancellationSignal signal) {
        if (actorId == null || signal == null) {
            return;
        }
        Long stamp = ACTIVE.get(actorId);
        if (stamp == null) {
            return;
        }
        SIGNALS.put(actorId, new Bound(stamp, signal));
    }

    /** Polled by adapters inside tick loops. A signal from an older generation is not consulted. */
    public static boolean cancelled(String actorId) {
        if (actorId == null) {
            return false;
        }
        Long stamp = ACTIVE.get(actorId);
        Bound bound = SIGNALS.get(actorId);
        return stamp != null && bound != null && bound.stamp() == stamp && bound.signal().isCancelled();
    }

    /**
     * Acquires, binds the call's cancellation signal, runs {@code operation} and releases on completion.
     * The supplier runs while the slot is held, so two concurrent calls cannot both touch the world.
     */
    public static <T> CompletableFuture<T> guarded(String actorId, CancellationSignal cancellation,
                                                   Supplier<CompletableFuture<T>> operation) {
        long[] stamp = new long[1];
        Optional<ToolException> refusal = acquire(actorId, stamp);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        bindCancellation(actorId, cancellation);
        CompletableFuture<T> future;
        try {
            future = operation.get();
        } catch (RuntimeException failure) {
            releaseOwned(actorId, stamp[0]);
            return CompletableFuture.failedFuture(failure);
        }
        if (future == null) {
            releaseOwned(actorId, stamp[0]);
            return CompletableFuture.failedFuture(new IllegalStateException("no operation started"));
        }
        return future.whenComplete((value, failure) -> releaseOwned(actorId, stamp[0]));
    }
}