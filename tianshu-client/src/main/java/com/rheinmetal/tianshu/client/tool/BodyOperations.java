package com.rheinmetal.tianshu.client.tool;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
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
 * <p>Each grant owns a cancellation signal and one expiry timer. Completion removes its timer immediately.
 * Late completion can only release the original grant.</p>
 */
public final class BodyOperations {

    private static final long STALE_NANOS = TimeUnit.MINUTES.toNanos(5);

    private static final Map<String, Slot> ACTIVE = new ConcurrentHashMap<>();
    private static final ScheduledThreadPoolExecutor EXPIRY = expiryExecutor();

    private static ScheduledThreadPoolExecutor expiryExecutor() {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "tianshu-body-expiry");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setKeepAliveTime(1, TimeUnit.MINUTES);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static final class Slot {
        private volatile CancellationSignal signal;
        private ScheduledFuture<?> expiry;

        private Slot(CancellationSignal signal) {
            this.signal = signal;
        }
    }

    private BodyOperations() {
    }

    private static Optional<ToolException> acquire(String actorId, Slot slot) {
        if (actorId == null || actorId.isBlank()) {
            return Optional.empty();
        }
        Slot winner = ACTIVE.computeIfAbsent(actorId, id -> {
            slot.expiry = EXPIRY.schedule(() -> releaseOwned(id, slot), STALE_NANOS, TimeUnit.NANOSECONDS);
            return slot;
        });
        return winner == slot ? Optional.empty() : Optional.of(new ToolException(ToolError.BUSY,
                "this body is already running an operation"));
    }

    public static Optional<ToolException> acquire(String actorId) {
        return acquire(actorId, new Slot(null));
    }

    public static void release(String actorId) {
        Slot slot = actorId == null ? null : ACTIVE.remove(actorId);
        if (slot != null) {
            slot.signal = null;
            slot.expiry.cancel(false);
        }
    }

    private static void releaseOwned(String actorId, Slot slot) {
        if (actorId != null && ACTIVE.remove(actorId, slot)) {
            slot.signal = null;
            slot.expiry.cancel(false);
        }
    }

    public static void bindCancellation(String actorId, CancellationSignal signal) {
        if (actorId != null && signal != null) {
            ACTIVE.computeIfPresent(actorId, (id, slot) -> {
                slot.signal = signal;
                return slot;
            });
        }
    }

    public static boolean cancelled(String actorId) {
        Slot slot = actorId == null ? null : ACTIVE.get(actorId);
        CancellationSignal signal = slot == null ? null : slot.signal;
        return signal != null && signal.isCancelled();
    }

    /**
     * Acquires, binds the call's cancellation signal, runs {@code operation} and releases on completion.
     * The supplier runs while the slot is held, so two concurrent calls cannot both touch the world.
     */
    public static <T> CompletableFuture<T> guarded(String actorId, CancellationSignal cancellation,
                                                   Supplier<CompletableFuture<T>> operation) {
        Slot slot = new Slot(cancellation);
        Optional<ToolException> refusal = acquire(actorId, slot);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        CompletableFuture<T> future;
        try {
            future = operation.get();
        } catch (RuntimeException failure) {
            releaseOwned(actorId, slot);
            return CompletableFuture.failedFuture(failure);
        }
        if (future == null) {
            releaseOwned(actorId, slot);
            return CompletableFuture.failedFuture(new IllegalStateException("no operation started"));
        }
        future.whenComplete((value, failure) -> releaseOwned(actorId, slot));
        return future.copy();
    }
}
