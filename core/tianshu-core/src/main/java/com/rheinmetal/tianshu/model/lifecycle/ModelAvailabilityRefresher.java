package com.rheinmetal.tianshu.model.lifecycle;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.model.ModelAvailabilitySnapshot;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ModuleExecutionAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Single-flight availability scan shared by every model front.
 *
 * <p>Each front publishes "which of my models are installed" to the settings page. The scan itself
 * is front specific, so it arrives as a {@link Supplier}; what is shared is the scheduling policy:
 * one scan at a time on the IO lane, callbacks coalesced into one batch, and a re-run when callers
 * arrive during a running scan.</p>
 *
 * <p>Keeping this in one place is what lets a fix to the refresh/coalescing rules reach ASR, LLM and
 * TTS at once instead of three drifting copies.</p>
 */
public final class ModelAvailabilityRefresher {

    private final String moduleId;
    private final ModuleExecutionAccess executor;
    private final IGameEnvironment env;
    private final Supplier<ModelAvailabilitySnapshot> scan;
    private final AtomicBoolean refreshQueued = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
    private final AtomicReference<ModelAvailabilitySnapshot> snapshot =
            new AtomicReference<>(ModelAvailabilitySnapshot.empty());

    public ModelAvailabilityRefresher(
            String moduleId,
            ModuleExecutionAccess executor,
            IGameEnvironment env,
            Supplier<ModelAvailabilitySnapshot> scan
    ) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.env = Objects.requireNonNull(env, "env");
        this.scan = Objects.requireNonNull(scan, "scan");
    }

    /** Latest published snapshot. Never null; starts as {@link ModelAvailabilitySnapshot#empty()}. */
    public ModelAvailabilitySnapshot snapshot() {
        return snapshot.get();
    }

    /**
     * Requests a background scan. While one scan is pending or running, further requests only add
     * their completion callback to the current batch.
     */
    public void refreshAsync(Runnable completion) {
        if (completion != null) {
            callbacks.add(completion);
        }
        if (!refreshQueued.compareAndSet(false, true)) {
            return;
        }
        ProtocolTaskHandle handle = executor.submit(
                ProtocolTaskSpec.builder()
                        .moduleId(moduleId)
                        .lane(ExecutionLane.IO)
                        .concurrencyKey(moduleId + ":model.availability")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                () -> {
                    try {
                        refreshNow();
                    } finally {
                        finishBatch();
                    }
                }
        );
        if (handle.state() == ProtocolTaskState.REJECTED) {
            finishBatch();
        }
    }

    /**
     * Runs the scan on the calling thread and publishes it. Callers already on the IO lane use this
     * to avoid a redundant dispatch.
     */
    public void refreshNow() {
        snapshot.set(scan.get());
    }

    private void finishBatch() {
        Runnable callback;
        while ((callback = callbacks.poll()) != null) {
            try {
                callback.run();
            } catch (RuntimeException failure) {
                env.error(moduleId + ".model.availability_callback_failed", failure);
            }
        }
        refreshQueued.set(false);
        if (!callbacks.isEmpty()) {
            refreshAsync(null);
        }
    }
}
