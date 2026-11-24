package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.observability.ProtocolExecutorSnapshot;
import com.rheinmetal.tianshu.protocol.observability.ProtocolLaneSnapshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;

public final class ProtocolExecutorManager implements ModuleExecutionAccess, AutoCloseable {
    /** How long {@link #close()} waits for interrupted work before giving up on it. */
    private static final long CLOSE_AWAIT_MILLIS = 1_000L;

    private final MainThreadExecutor mainThreadExecutor;
    private final Map<ExecutionLane, ThreadPoolExecutor> executors = new EnumMap<>(ExecutionLane.class);
    private final Map<String, TaskGroup> taskGroups = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<ManagedTaskHandle> activeHandles = ConcurrentHashMap.newKeySet();
    private final ScheduledThreadPoolExecutor scheduledExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ProtocolExecutorManager(MainThreadExecutor mainThreadExecutor) {
        this(mainThreadExecutor, ProtocolExecutorPolicy.defaults());
    }

    public ProtocolExecutorManager(MainThreadExecutor mainThreadExecutor, ProtocolExecutorPolicy executorPolicy) {
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
        ProtocolExecutorPolicy effectivePolicy = executorPolicy == null ? ProtocolExecutorPolicy.defaults() : executorPolicy;
        for (ExecutionLane lane : ExecutionLane.values()) {
            if (lane == ExecutionLane.MAIN || lane == ExecutionLane.SCHEDULED) {
                continue;
            }
            ProtocolExecutorPolicy.LanePolicy lanePolicy = effectivePolicy.lane(lane);
            executors.put(lane, fixedExecutor(lane, lanePolicy.threads(), lanePolicy.queueCapacity()));
        }
        this.scheduledExecutor = new ScheduledThreadPoolExecutor(effectivePolicy.scheduledThreads(), threadFactory(ExecutionLane.SCHEDULED));
        this.scheduledExecutor.setRemoveOnCancelPolicy(true);
    }

    public ProtocolTaskHandle submit(ProtocolTaskSpec spec, Runnable task) {
        Objects.requireNonNull(task, "task");
        return submit(spec, () -> {
            task.run();
            return null;
        }, null);
    }

    public <T> ProtocolTaskHandle submit(ProtocolTaskSpec spec, Callable<T> task) {
        return submit(spec, task, null);
    }

    /** A void task with a settlement callback; the value-returning overload cannot express a {@code Runnable}. */
    public ProtocolTaskHandle submit(ProtocolTaskSpec spec, Runnable task, Runnable onSettled) {
        Objects.requireNonNull(task, "task");
        return submit(spec, () -> {
            task.run();
            return null;
        }, onSettled);
    }

    /**
     * Submits a task and reports exactly once when the manager is done with it.
     *
     * <p>{@code onSettled} runs whether the body ran, the task was cancelled before it started, or the submission was
     * rejected. Callers hang their own bookkeeping on it instead of guessing from the handle's state: a task cancelled
     * while queued never enters its body, so a {@code finally} inside the body is not a reliable release point.</p>
     */
    public <T> ProtocolTaskHandle submit(ProtocolTaskSpec spec, Callable<T> task, Runnable onSettled) {
        ProtocolTaskSpec effective = Objects.requireNonNull(spec, "spec");
        Callable<T> effectiveTask = Objects.requireNonNull(task, "task");
        ManagedTaskHandle handle = new ManagedTaskHandle(effective);
        handle.onSettled = onSettled;
        handle.onPrevented = () -> settle(handle);
        activeHandles.add(handle);
        if (closed.get()) {
            rejectClosed(handle);
            return handle;
        }
        if (effective.lane() == ExecutionLane.MAIN) {
            if (handle.state.compareAndSet(ProtocolTaskState.ACCEPTED, ProtocolTaskState.QUEUED)) {
                try {
                    mainThreadExecutor.execute(() -> runManaged(handle, effectiveTask));
                } catch (RejectedExecutionException rejected) {
                    handle.setFailureCause(rejected);
                    handle.state.compareAndSet(ProtocolTaskState.QUEUED, ProtocolTaskState.REJECTED);
                    settle(handle);
                }
            }
            return handle;
        }
        if (effective.lane() == ExecutionLane.SCHEDULED) {
            handle.setFailureCause(new RejectedExecutionException(
                    "SCHEDULED lane submit is not supported; use schedule"));
            handle.reject();
            settle(handle);
            return handle;
        }
        TaskGroup group;
        boolean admitted;
        synchronized (taskGroups) {
            if (handle.isDone()) {
                return handle;
            }
            group = taskGroups.computeIfAbsent(effective.concurrencyKey(), TaskGroup::new);
            admitted = group.offer(new QueuedTask<>(handle, effectiveTask));
        }
        if (admitted) {
            group.drain();
        } else {
            handle.reject();
            settle(handle);
            group.drain();
        }
        return handle;
    }

    public ProtocolTaskHandle schedule(ProtocolTaskSpec spec, Runnable task, Duration delay) {
        return schedule(spec, task, delay, null);
    }

    /** Schedules a timer and reports exactly once when the manager is done with it. */
    public ProtocolTaskHandle schedule(ProtocolTaskSpec spec, Runnable task, Duration delay, Runnable onSettled) {
        Objects.requireNonNull(task, "task");
        ProtocolTaskSpec effective = Objects.requireNonNull(spec, "spec");
        ManagedTaskHandle handle = new ManagedTaskHandle(effective);
        handle.onSettled = onSettled;
        handle.onPrevented = () -> settle(handle);
        activeHandles.add(handle);
        if (closed.get()) {
            rejectClosed(handle);
            return handle;
        }
        long delayMillis = Math.max(0L, delay == null ? 0L : delay.toMillis());
        Runnable wrapped = () -> runManaged(handle, () -> {
            task.run();
            return null;
        });
        try {
            if (!handle.state.compareAndSet(ProtocolTaskState.ACCEPTED, ProtocolTaskState.QUEUED)) {
                return handle;
            }
            ScheduledFuture<?> future = scheduledExecutor.schedule(wrapped, delayMillis, TimeUnit.MILLISECONDS);
            handle.setFuture(future);
            return handle;
        } catch (RejectedExecutionException exception) {
            handle.setFailureCause(exception);
            handle.reject();
            settle(handle);
            return handle;
        }
    }

    /**
     * Ends one handle's life here, exactly once.
     *
     * <p>Every path that can leave a task behind calls this: the body's exit, a cancel that prevented the body, and a
     * rejected submission. One place is what makes "the manager let go and the caller was told" true regardless of
     * which of those happened.</p>
     */
    private void settle(ManagedTaskHandle handle) {
        if (!handle.settled.compareAndSet(false, true)) {
            return;
        }
        activeHandles.remove(handle);
        Runnable release = handle.releaseGroup;
        Runnable action = handle.onSettled;
        handle.releaseGroup = null;
        handle.onSettled = null;
        handle.onPrevented = null;
        if (release != null) {
            release.run();
        }
        if (action != null) {
            action.run();
        }
    }

    private void rejectClosed(ManagedTaskHandle handle) {
        handle.setFailureCause(new RejectedExecutionException("Protocol executor manager is closed"));
        handle.reject();
        settle(handle);
    }

    /**
     * How many task handles this manager is currently holding.
     *
     * <p>Exposed because "the queue drained but the manager still holds three handles" was invisible from outside: a
     * scheduled task cancelled before it fires keeps its handle unless something drops it, and every diagnostic of that
     * leak needed a heap dump. This counter is the cheap version.</p>
     */
    public int activeTaskCount() {
        return activeHandles.size();
    }

    public ProtocolExecutorSnapshot snapshot() {
        List<ProtocolLaneSnapshot> lanes = new ArrayList<>();
        int running = 0;
        int queued = scheduledExecutor.getQueue().size();
        for (Map.Entry<ExecutionLane, ThreadPoolExecutor> entry : executors.entrySet()) {
            ThreadPoolExecutor executor = entry.getValue();
            running += executor.getActiveCount();
            queued += executor.getQueue().size();
            lanes.add(new ProtocolLaneSnapshot(entry.getKey(), executor.getPoolSize(), executor.getActiveCount(), executor.getQueue().size(), executor.getCompletedTaskCount(), 0L));
        }
        for (TaskGroup group : taskGroups.values()) {
            queued += group.queuedCount();
        }
        lanes.add(new ProtocolLaneSnapshot(ExecutionLane.SCHEDULED, scheduledExecutor.getPoolSize(), scheduledExecutor.getActiveCount(), scheduledExecutor.getQueue().size(), scheduledExecutor.getCompletedTaskCount(), 0L));
        lanes.add(new ProtocolLaneSnapshot(ExecutionLane.MAIN, 0, 0, 0, 0L, 0L));
        return new ProtocolExecutorSnapshot(lanes, running, queued);
    }

    private <T> void runManaged(ManagedTaskHandle handle, Callable<T> task) {
        if (!handle.state.compareAndSet(ProtocolTaskState.QUEUED, ProtocolTaskState.RUNNING)) {
            settle(handle);
            return;
        }
        try {
            task.call();
            if (handle.state() != ProtocolTaskState.CANCELLED) {
                handle.state.compareAndSet(ProtocolTaskState.RUNNING, ProtocolTaskState.COMPLETED);
            }
        } catch (Throwable throwable) {
            // Every exit must leave a state behind: a handle stuck in RUNNING makes isDone() lie forever,
            // and native backends fail with Error (NoClassDefFoundError, OOM), not Exception.
            if (handle.state() != ProtocolTaskState.CANCELLED) {
                handle.setFailureCause(throwable);
                handle.state.compareAndSet(ProtocolTaskState.RUNNING, ProtocolTaskState.FAILED);
            }
            if (throwable instanceof Error error) {
                throw error;
            }
        } finally {
            settle(handle);
        }
    }

    private ThreadPoolExecutor executorFor(ExecutionLane lane) {
        if (lane == ExecutionLane.MAIN) {
            throw new IllegalArgumentException("MAIN lane is dispatched through MainThreadExecutor");
        }
        if (lane == ExecutionLane.SCHEDULED) {
            throw new IllegalArgumentException("SCHEDULED lane must use schedule");
        }
        ThreadPoolExecutor executor = executors.get(lane);
        if (executor == null) {
            throw new IllegalArgumentException("Unsupported execution lane: " + lane);
        }
        return executor;
    }

    private static ThreadPoolExecutor fixedExecutor(ExecutionLane lane, int threads, int queueCapacity) {
        return new ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(Math.max(1, queueCapacity)),
            threadFactory(lane),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static ThreadFactory threadFactory(ExecutionLane lane) {
        return runnable -> {
            Thread thread = new Thread(runnable, "Tianshu-Protocol-" + lane.name());
            thread.setDaemon(true);
            return thread;
        };
    }

    private final class TaskGroup {
        private final String key;
        private final PriorityBlockingQueue<QueuedTask<?>> queue = new PriorityBlockingQueue<>();
        private final AtomicBoolean draining = new AtomicBoolean(false);
        /**
         * The handles taking up this group's concurrency budget.
         *
         * <p>Handles rather than a counter: a handle cancelled before its wrapper starts never runs, so nothing would
         * ever decrement a counter — a {@code Future.cancel(true)} from a broker's cancel path would silently eat one
         * slot of this group's budget for the rest of the session. A handle that is already done stops counting the
         * moment it is asked.</p>
         */
        private final Set<ProtocolTaskHandle> inFlight = new java.util.LinkedHashSet<>();

        private TaskGroup(String key) {
            this.key = key;
        }

        private boolean offer(QueuedTask<?> task) {
            synchronized (this) {
                if (queue.size() >= task.handle.spec.queueCapacity()) {
                    return false;
                }
                if (!task.handle.state.compareAndSet(ProtocolTaskState.ACCEPTED, ProtocolTaskState.QUEUED)) {
                    return false;
                }
                task.handle.releaseGroup = () -> finishOne(task);
                if (task.handle.settled.get()) {
                    task.handle.releaseGroup = null;
                    return false;
                }
                queue.offer(task);
            }
            return true;
        }

        private void drain() {
            if (!draining.compareAndSet(false, true)) {
                return;
            }
            try {
                while (true) {
                    QueuedTask<?> task;
                    synchronized (this) {
                        task = queue.peek();
                        if (task == null || inFlight.size() >= task.handle.spec.maxConcurrency()) {
                            break;
                        }
                        queue.poll();
                        inFlight.add(task.handle);
                    }
                    submitToLane(task);
                }
            } finally {
                draining.set(false);
                boolean shouldDrainAgain;
                synchronized (taskGroups) {
                    synchronized (this) {
                        shouldDrainAgain = !queue.isEmpty() && inFlight.size() < queue.peek().handle.spec.maxConcurrency();
                        if (inFlight.isEmpty() && queue.isEmpty()) {
                            taskGroups.remove(key, this);
                        }
                    }
                }
                if (shouldDrainAgain) {
                    drain();
                }
            }
        }

        private <T> void submitToLane(QueuedTask<T> task) {
            ThreadPoolExecutor executor = executorFor(task.handle.spec.lane());
            try {
                Future<?> future = executor.submit(() -> runManaged(task.handle, task.callable));
                task.handle.setFuture(future);
            } catch (RejectedExecutionException exception) {
                task.handle.setFailureCause(exception);
                task.handle.reject();
                settle(task.handle);
            }
        }

        private void finishOne(QueuedTask<?> task) {
            synchronized (this) {
                if (!inFlight.remove(task.handle)) {
                    queue.remove(task);
                }
            }
            drain();
        }

        private int queuedCount() {
            synchronized (this) {
                return queue.size();
            }
        }


    }

    private static final class QueuedTask<T> implements Comparable<QueuedTask<?>> {
        private static final AtomicLong SEQUENCE = new AtomicLong();
        private final ManagedTaskHandle handle;
        private final Callable<T> callable;
        private final long sequence = SEQUENCE.incrementAndGet();

        private QueuedTask(ManagedTaskHandle handle, Callable<T> callable) {
            this.handle = handle;
            this.callable = callable;
        }

        @Override
        public int compareTo(QueuedTask<?> other) {
            int priorityCompare = Integer.compare(other.handle.spec.priority().weight(), handle.spec.priority().weight());
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return Long.compare(sequence, other.sequence);
        }
    }

    /** Maintenance shares this session's scheduler and ends with its executor. */
    void scheduleMaintenance(Runnable task, long periodMillis) {
        scheduledExecutor.scheduleWithFixedDelay(task, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (ManagedTaskHandle handle : activeHandles) {
                handle.cancel("executor_closed");
            }
            taskGroups.clear();
            for (ThreadPoolExecutor executor : executors.values()) {
                executor.shutdownNow();
            }
            scheduledExecutor.shutdownNow();
            awaitTermination();
        }
    }

    /** Gives interrupted work one bounded window to finish before the caller releases its resources. */
    private void awaitTermination() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_AWAIT_MILLIS);
        for (ThreadPoolExecutor executor : executors.values()) {
            awaitQuietly(executor, deadline);
        }
        awaitQuietly(scheduledExecutor, deadline);
    }

    private static void awaitQuietly(java.util.concurrent.ExecutorService executor, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
            return;
        }
        try {
            executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ManagedTaskHandle implements ProtocolTaskHandle {
        private final ProtocolTaskSpec spec;
        private final AtomicReference<ProtocolTaskState> state = new AtomicReference<>(ProtocolTaskState.ACCEPTED);
        private final AtomicReference<Throwable> failureCause = new AtomicReference<>();
        private volatile Future<?> future;
        /** Set once by {@code settle}: the handle's life ends exactly one way, whoever gets there first. */
        private final AtomicBoolean settled = new AtomicBoolean();
        /** The caller's bookkeeping, run by {@code settle}. */
        private volatile Runnable onSettled;
        private volatile Runnable releaseGroup;
        /**
         * Runs when a cancel prevents the work from ever starting.
         *
         * <p>A cancelled task that never entered its body never reaches the {@code finally} that would give back its
         * slot or drop it from the active set, so the cancel itself has to. A task that was already running is
         * different: its body exits through that {@code finally}, which is what keeps a serial group serial — the
         * caller seeing CANCELLED must not mean the worker has stopped touching shared state.</p>
         */
        private volatile Runnable onPrevented;

        private ManagedTaskHandle(ProtocolTaskSpec spec) {
            this.spec = spec;
        }

        private void setFuture(Future<?> future) {
            this.future = future;
            if (state() == ProtocolTaskState.CANCELLED) {
                future.cancel(spec.interruptible());
            }
        }

        private void reject() {
            state.compareAndSet(ProtocolTaskState.ACCEPTED, ProtocolTaskState.REJECTED);
            state.compareAndSet(ProtocolTaskState.QUEUED, ProtocolTaskState.REJECTED);
        }

        private void setFailureCause(Throwable failureCause) {
            this.failureCause.set(failureCause);
        }

        @Override
        public String taskId() {
            return spec.taskId();
        }

        @Override
        public String moduleId() {
            return spec.moduleId();
        }

        @Override
        public String envelopeId() {
            return spec.envelopeId();
        }

        @Override
        public ExecutionLane lane() {
            return spec.lane();
        }

        @Override
        public ProtocolTaskState state() {
            return state.get();
        }

        @Override
        public Optional<Throwable> failureCause() {
            return Optional.ofNullable(failureCause.get());
        }

        @Override
        public boolean cancel(String reason) {
            ProtocolTaskState previous;
            do {
                previous = state.get();
                if (previous != ProtocolTaskState.ACCEPTED && previous != ProtocolTaskState.QUEUED
                        && previous != ProtocolTaskState.RUNNING) {
                    return false;
                }
            } while (!state.compareAndSet(previous, ProtocolTaskState.CANCELLED));
            Future<?> current = future;
            if (current != null) {
                current.cancel(spec.interruptible());
            }
            if (previous != ProtocolTaskState.RUNNING) {
                Runnable prevented = onPrevented;
                if (prevented != null) {
                    prevented.run();
                }
            }
            return true;
        }

        @Override
        public boolean isDone() {
            ProtocolTaskState current = state();
            return current == ProtocolTaskState.COMPLETED || current == ProtocolTaskState.FAILED || current == ProtocolTaskState.CANCELLED || current == ProtocolTaskState.REJECTED;
        }

        @Override
        public boolean isRunning() {
            return state() == ProtocolTaskState.RUNNING;
        }
    }
}
