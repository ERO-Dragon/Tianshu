package com.rheinmetal.tianshu.function.llm.gateway;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Consumer;

public final class LlmGatewayScheduler {
    private final LlmGatewayPolicy policy;
    private final PriorityQueue<LlmGatewayTask> pendingQueue;
    private final Map<String, LlmGatewayTask> submittedTasks = new LinkedHashMap<>();

    public LlmGatewayScheduler(LlmGatewayPolicy policy) {
        this.policy = policy == null ? LlmGatewayPolicy.DEFAULT : policy;
        this.pendingQueue = new PriorityQueue<>(Comparator
                .comparingInt((LlmGatewayTask task) -> task.request().taskPriority()).reversed()
                .thenComparingLong(task -> task.request().createdAtMillis())
                .thenComparing(task -> task.request().taskId()));
    }

    public synchronized ScheduleDecision schedule(LlmGatewayTask task) {
        if (task == null) {
            return ScheduleDecision.rejected("INVALID_TASK", "LLM gateway task is missing");
        }
        if (shouldSubmitImmediately(task)) {
            submittedTasks.put(task.request().taskId(), task);
            task.transitionTo(LlmGatewayTaskState.SUBMITTED);
            return ScheduleDecision.submitNow(task);
        }
        if (pendingQueue.size() >= policy.maxPendingTasks()) {
            return ScheduleDecision.rejected("GATEWAY_QUEUE_FULL", "LLM gateway pending queue is full");
        }
        pendingQueue.add(task);
        task.transitionTo(LlmGatewayTaskState.QUEUED);
        return ScheduleDecision.queued(task);
    }

    public synchronized void completeSubmitted(String taskId) {
        submittedTasks.remove(taskId);
    }

    public synchronized List<LlmGatewayTask> drainReadyTasks(long now) {
        List<LlmGatewayTask> ready = new ArrayList<>();
        while (submittedTasks.isEmpty() && !pendingQueue.isEmpty()) {
            LlmGatewayTask task = pendingQueue.poll();
            if (task.request().isExpired(now)) {
                task.transitionTo(LlmGatewayTaskState.EXPIRED);
                ready.add(task);
                continue;
            }
            submittedTasks.put(task.request().taskId(), task);
            task.transitionTo(LlmGatewayTaskState.SUBMITTED);
            ready.add(task);
        }
        return ready;
    }

    public synchronized LlmGatewayTask cancelPending(String taskId) {
        LlmGatewayTask task = removePending(taskId);
        if (task != null) {
            task.transitionTo(LlmGatewayTaskState.CANCELLED);
        }
        return task;
    }

    public synchronized LlmGatewayTask submitted(String taskId) {
        return submittedTasks.get(taskId);
    }

    public synchronized int pendingCount() {
        return pendingQueue.size();
    }

    public synchronized int submittedCount() {
        return submittedTasks.size();
    }

    public synchronized int pendingCountForSource(String sourceId) {
        int count = 0;
        for (LlmGatewayTask task : pendingQueue) {
            if (task.request().sourceId().equals(sourceId)) {
                count++;
            }
        }
        return count;
    }

    public synchronized void cancelAll(Consumer<LlmGatewayTask> terminalConsumer) {
        while (!pendingQueue.isEmpty()) {
            LlmGatewayTask task = pendingQueue.poll();
            task.transitionTo(LlmGatewayTaskState.CANCELLED);
            if (terminalConsumer != null) {
                terminalConsumer.accept(task);
            }
        }
        for (LlmGatewayTask task : submittedTasks.values()) {
            task.transitionTo(LlmGatewayTaskState.CANCELLED);
            if (task.invocationHandle() != null) {
                task.invocationHandle().cancel();
            }
            if (terminalConsumer != null) {
                terminalConsumer.accept(task);
            }
        }
        submittedTasks.clear();
    }

    private boolean shouldSubmitImmediately(LlmGatewayTask task) {
        return submittedTasks.isEmpty();
    }

    private LlmGatewayTask removePending(String taskId) {
        if (taskId == null) {
            return null;
        }
        List<LlmGatewayTask> retained = new ArrayList<>();
        LlmGatewayTask removed = null;
        while (!pendingQueue.isEmpty()) {
            LlmGatewayTask task = pendingQueue.poll();
            if (removed == null && task.request().taskId().equals(taskId)) {
                removed = task;
            } else {
                retained.add(task);
            }
        }
        pendingQueue.addAll(retained);
        return removed;
    }

    public record ScheduleDecision(boolean submitNow, boolean queued, LlmGatewayTask task, LlmGatewayError error) {
        public static ScheduleDecision submitNow(LlmGatewayTask task) {
            return new ScheduleDecision(true, false, task, null);
        }

        public static ScheduleDecision queued(LlmGatewayTask task) {
            return new ScheduleDecision(false, true, task, null);
        }

        public static ScheduleDecision rejected(String code, String message) {
            return new ScheduleDecision(false, false, null, new LlmGatewayError(code, message));
        }
    }
}
