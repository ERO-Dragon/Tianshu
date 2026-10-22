package com.rheinmetal.tianshu.model.lifecycle;

import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ModuleExecutionAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one model deletion per module on the IO lane.
 *
 * <p>Every model front needs the same four steps around a deletion: refuse a second deletion while
 * one is running, do the removal off the calling thread, republish availability afterwards, and
 * report the result. Only the removal itself differs, so it arrives as a {@link DeleteAction}.</p>
 *
 * <p>The permit is released on every exit path — success, failure, and a rejected lane submission.
 * A deletion that never released it would leave the settings page's delete button disabled for the
 * rest of the session.</p>
 */
public final class ModelDeletionRunner {

    /** The front-specific removal. Returns whether anything was deleted. */
    @FunctionalInterface
    public interface DeleteAction {
        boolean delete();
    }

    private final String moduleId;
    private final ModuleExecutionAccess executor;
    private final ModelAvailabilityRefresher refresher;
    private final AtomicBoolean deleting = new AtomicBoolean(false);

    public ModelDeletionRunner(
            String moduleId,
            ModuleExecutionAccess executor,
            ModelAvailabilityRefresher refresher
    ) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.refresher = Objects.requireNonNull(refresher, "refresher");
    }

    /** Whether a deletion is currently running for this module. */
    public boolean isDeleting() {
        return deleting.get();
    }

    /**
     * Starts a deletion, or reports {@code false} immediately when one is already running.
     *
     * @param deleteAction performs the removal on the IO lane
     * @param callback receives the result; may be null
     */
    public void deleteAsync(DeleteAction deleteAction, ModelDeleteCallback callback) {
        Objects.requireNonNull(deleteAction, "deleteAction");
        if (!deleting.compareAndSet(false, true)) {
            notify(callback, false);
            return;
        }
        ProtocolTaskHandle handle = executor.submit(
                ProtocolTaskSpec.builder()
                        .moduleId(moduleId)
                        .lane(ExecutionLane.IO)
                        .concurrencyKey(moduleId + ":model.delete")
                        .maxConcurrency(1)
                        .queueCapacity(2)
                        .build(),
                () -> {
                    boolean deleted;
                    try {
                        deleted = deleteAction.delete();
                        refresher.refreshNow();
                    } finally {
                        deleting.set(false);
                    }
                    notify(callback, deleted);
                }
        );
        if (handle.state() == ProtocolTaskState.REJECTED) {
            deleting.set(false);
            notify(callback, false);
        }
    }

    private static void notify(ModelDeleteCallback callback, boolean deleted) {
        if (callback != null) {
            callback.onComplete(deleted);
        }
    }
}
