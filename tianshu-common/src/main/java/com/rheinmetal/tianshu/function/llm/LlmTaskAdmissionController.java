package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.ITianshuConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

final class LlmTaskAdmissionController {
    private final Object lock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final int maxWaitingTasks;
    private final int agingBoostPerRequest;
    private final List<AdmissionTask> waitingTasks = new ArrayList<>();
    private final List<AdmissionTask> inFlightTasks = new ArrayList<>();
    private AdmissionTask activeTask;

    LlmTaskAdmissionController(int maxWaitingTasks) {
        this(maxWaitingTasks, 1);
    }

    LlmTaskAdmissionController(int maxWaitingTasks, int agingBoostPerRequest) {
        this.maxWaitingTasks = Math.max(0, maxWaitingTasks);
        this.agingBoostPerRequest = Math.max(0, agingBoostPerRequest);
    }

    static LlmTaskAdmissionController fromConfig(ITianshuConfig config) {
        int waitingTasks = config == null ? 0 : config.getLlmTaskAdmissionQueueSize();
        int agingBoost = config == null ? 1 : config.getLlmTaskAgingBoostPerRequest();
        return new LlmTaskAdmissionController(waitingTasks, agingBoost);
    }

    AdmissionResult submit(int priority, boolean preemptible, TaskLauncher launcher, TaskRejectionHandler onRejected) {
        Objects.requireNonNull(launcher, "launcher");
        AdmissionTask incoming = new AdmissionTask(priority, preemptible, sequence.incrementAndGet(), launcher, onRejected);
        AdmissionTask toLaunch = null;
        List<AdmissionTask> rejected = new ArrayList<>();
        AdmissionState state;

        synchronized (lock) {
            ageWaitingTasks();
            if (activeTask == null && inFlightTasks.isEmpty()) {
                activeTask = incoming;
                markInFlight(incoming);
                toLaunch = incoming;
                state = AdmissionState.STARTED;
            } else {
                AdmissionTask candidate = bestPreemptionCandidate(incoming);
                if (activeTask != null && activeTask.preemptible() && canPreempt(activeTask, candidate)) {
                    if (candidate == incoming) {
                        activeTask = incoming;
                        markInFlight(incoming);
                        toLaunch = incoming;
                        state = AdmissionState.STARTED;
                    } else {
                        waitingTasks.remove(candidate);
                        activeTask = candidate;
                        markInFlight(candidate);
                        toLaunch = candidate;
                        state = admitWaiting(incoming, rejected);
                    }
                } else {
                    state = admitWaiting(incoming, rejected);
                }
            }
        }

        rejected.forEach(task -> task.reject("LLM_TASK_QUEUE_FULL", "LLM task queue is full"));
        if (toLaunch != null) {
            launch(toLaunch);
        }
        return new AdmissionResult(state);
    }

    void clearWaitingTasks() {
        clearWaitingTasks("LLM_SERVICE_NOT_READY", "LLM service is not initialized");
    }

    void clearWaitingTasks(String code, String message) {
        List<AdmissionTask> rejected;
        synchronized (lock) {
            rejected = new ArrayList<>(waitingTasks);
            waitingTasks.clear();
        }
        rejected.forEach(task -> task.reject(code, message));
    }

    private void ageWaitingTasks() {
        for (AdmissionTask task : waitingTasks) {
            task.age();
        }
    }

    private AdmissionState admitWaiting(AdmissionTask incoming, List<AdmissionTask> rejected) {
        if (waitingTasks.size() < maxWaitingTasks) {
            waitingTasks.add(incoming);
            return AdmissionState.QUEUED;
        }
        AdmissionTask replaceable = worstWaitingTask();
        if (replaceable != null && incoming.betterThan(replaceable, agingBoostPerRequest)) {
            waitingTasks.remove(replaceable);
            waitingTasks.add(incoming);
            rejected.add(replaceable);
            return AdmissionState.QUEUED;
        }
        rejected.add(incoming);
        return AdmissionState.REJECTED;
    }

    private boolean canPreempt(AdmissionTask currentActive, AdmissionTask candidate) {
        return currentActive != null
                && candidate != null
                && candidate.effectivePriority(agingBoostPerRequest) > currentActive.priority();
    }

    private AdmissionTask bestPreemptionCandidate(AdmissionTask incoming) {
        AdmissionTask best = bestWaitingTask();
        if (best == null || incoming.betterThan(best, agingBoostPerRequest)) {
            return incoming;
        }
        return best;
    }

    private AdmissionTask bestWaitingTask() {
        AdmissionTask best = null;
        for (AdmissionTask task : waitingTasks) {
            if (best == null || task.betterThan(best, agingBoostPerRequest)) {
                best = task;
            }
        }
        return best;
    }

    private AdmissionTask worstWaitingTask() {
        AdmissionTask worst = null;
        for (AdmissionTask task : waitingTasks) {
            if (worst == null || worst.betterThan(task, agingBoostPerRequest)) {
                worst = task;
            }
        }
        return worst;
    }

    private AdmissionTask removeBestWaitingTask() {
        AdmissionTask best = bestWaitingTask();
        if (best != null) {
            waitingTasks.remove(best);
        }
        return best;
    }

    private AdmissionTask bestInFlightTask() {
        AdmissionTask best = null;
        for (AdmissionTask task : inFlightTasks) {
            if (best == null || task.betterThan(best, agingBoostPerRequest)) {
                best = task;
            }
        }
        return best;
    }

    private void markInFlight(AdmissionTask task) {
        if (task != null && !inFlightTasks.contains(task)) {
            inFlightTasks.add(task);
        }
    }

    private void launch(AdmissionTask task) {
        CompletableFuture<?> future;
        try {
            future = task.launcher().launch();
        } catch (Exception e) {
            future = CompletableFuture.failedFuture(e);
        }
        if (future == null) {
            future = CompletableFuture.completedFuture(null);
        }
        future.whenComplete((ignored, throwable) -> finish(task));
    }

    private void finish(AdmissionTask completedTask) {
        AdmissionTask next = null;
        synchronized (lock) {
            inFlightTasks.remove(completedTask);
            if (activeTask == completedTask) {
                activeTask = bestInFlightTask();
            }
            if (activeTask == null && inFlightTasks.isEmpty() && !waitingTasks.isEmpty()) {
                next = removeBestWaitingTask();
                activeTask = next;
                markInFlight(next);
            }
        }
        if (next != null) {
            launch(next);
        }
    }

    @FunctionalInterface
    interface TaskLauncher {
        CompletableFuture<?> launch();
    }

    @FunctionalInterface
    interface TaskRejectionHandler {
        void reject(String code, String message);
    }

    record AdmissionResult(AdmissionState state) {
        boolean queued() {
            return state == AdmissionState.QUEUED;
        }

        boolean rejected() {
            return state == AdmissionState.REJECTED;
        }
    }

    enum AdmissionState {
        STARTED,
        QUEUED,
        REJECTED
    }

    private record AdmissionTask(
            int priority,
            boolean preemptible,
            long sequence,
            TaskLauncher launcher,
            TaskRejectionHandler onRejected,
            AtomicLong waitRequests
    ) {
        private AdmissionTask(int priority, boolean preemptible, long sequence, TaskLauncher launcher, TaskRejectionHandler onRejected) {
            this(priority, preemptible, sequence, launcher, onRejected, new AtomicLong());
        }

        void age() {
            waitRequests.incrementAndGet();
        }

        long effectivePriority(int agingBoostPerRequest) {
            return (long) priority + waitRequests.get() * (long) Math.max(0, agingBoostPerRequest);
        }

        boolean betterThan(AdmissionTask other, int agingBoostPerRequest) {
            long selfPriority = effectivePriority(agingBoostPerRequest);
            long otherPriority = other.effectivePriority(agingBoostPerRequest);
            if (selfPriority != otherPriority) {
                return selfPriority > otherPriority;
            }
            return sequence < other.sequence;
        }

        void reject(String code, String message) {
            if (onRejected != null) {
                onRejected.reject(code, message);
            }
        }
    }
}
