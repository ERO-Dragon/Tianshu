package com.rheinmetal.tianshu.function.ia.runtime;

import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;

import java.time.Duration;
import java.util.Objects;

public final class OwnerPreviewRefreshCoordinator {
    private static final String STOP_REASON = "IA_OWNER_PREVIEW_STOPPED";

    private final Object lifecycleLock = new Object();
    private final DelayedScheduler scheduler;
    private final Duration interval;
    private final Runnable refreshAction;

    private boolean active;
    private long generation;
    private ProtocolTaskHandle pendingTask;

    public OwnerPreviewRefreshCoordinator(DelayedScheduler scheduler, Duration interval, Runnable refreshAction) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.refreshAction = Objects.requireNonNull(refreshAction, "refreshAction");
    }

    public void start() {
        long currentGeneration;
        synchronized (lifecycleLock) {
            if (active) {
                return;
            }
            active = true;
            currentGeneration = ++generation;
        }
        schedule(currentGeneration);
    }

    public void stop() {
        ProtocolTaskHandle taskToCancel;
        synchronized (lifecycleLock) {
            if (!active && pendingTask == null) {
                return;
            }
            active = false;
            generation++;
            taskToCancel = pendingTask;
            pendingTask = null;
        }
        if (taskToCancel != null && !taskToCancel.isDone()) {
            taskToCancel.cancel(STOP_REASON);
        }
    }

    private void schedule(long expectedGeneration) {
        synchronized (lifecycleLock) {
            if (!current(expectedGeneration)) {
                return;
            }
            pendingTask = scheduler.schedule(() -> refresh(expectedGeneration), interval);
        }
    }

    private void refresh(long expectedGeneration) {
        synchronized (lifecycleLock) {
            if (!current(expectedGeneration)) {
                return;
            }
            pendingTask = null;
            try {
                refreshAction.run();
            } catch (RuntimeException | Error failure) {
                active = false;
                generation++;
                throw failure;
            }
            schedule(expectedGeneration);
        }
    }

    private boolean current(long expectedGeneration) {
        return active && generation == expectedGeneration;
    }

    @FunctionalInterface
    public interface DelayedScheduler {
        ProtocolTaskHandle schedule(Runnable task, Duration delay);
    }
}
