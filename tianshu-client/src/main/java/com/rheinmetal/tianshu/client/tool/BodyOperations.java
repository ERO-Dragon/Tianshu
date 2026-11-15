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
 */
public final class BodyOperations {

    private static final long STALE_NANOS = TimeUnit.MINUTES.toNanos(5);

    private static final Map<String, Long> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<String, CancellationSignal> SIGNALS = new ConcurrentHashMap<>();

    private BodyOperations() {
    }

    /** Takes the body's operation slot; a second concurrent long operation is refused with {@code BUSY}. */
    public static Optional<ToolException> acquire(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            return Optional.empty();
        }
        long now = System.nanoTime();
        Long previous = ACTIVE.putIfAbsent(actorId, now);
        if (previous == null) {
            return Optional.empty();
        }
        if (now - previous > STALE_NANOS) {
            ACTIVE.put(actorId, now);
            SIGNALS.remove(actorId);
            return Optional.empty();
        }
        return Optional.of(new ToolException(ToolError.BUSY,
                "this body is already running an operation"));
    }

    /** Releases the slot and forgets the signal; safe to call twice. */
    public static void release(String actorId) {
        if (actorId == null) {
            return;
        }
        ACTIVE.remove(actorId);
        SIGNALS.remove(actorId);
    }

    public static void bindCancellation(String actorId, CancellationSignal signal) {
        if (actorId == null || signal == null) {
            return;
        }
        SIGNALS.put(actorId, signal);
    }

    /** Polled by adapters inside tick loops. */
    public static boolean cancelled(String actorId) {
        CancellationSignal signal = actorId == null ? null : SIGNALS.get(actorId);
        return signal != null && signal.isCancelled();
    }

    /**
     * Acquires, binds the call's cancellation signal, runs {@code operation} and releases on completion.
     * The supplier runs while the slot is held, so two concurrent calls cannot both touch the world.
     */
    public static <T> CompletableFuture<T> guarded(String actorId, CancellationSignal cancellation,
                                                   Supplier<CompletableFuture<T>> operation) {
        Optional<ToolException> refusal = acquire(actorId);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        bindCancellation(actorId, cancellation);
        CompletableFuture<T> future;
        try {
            future = operation.get();
        } catch (RuntimeException failure) {
            release(actorId);
            return CompletableFuture.failedFuture(failure);
        }
        if (future == null) {
            release(actorId);
            return CompletableFuture.failedFuture(new IllegalStateException("no operation started"));
        }
        return future.whenComplete((value, failure) -> release(actorId));
    }
}