package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.observability.ProtocolExecutorSnapshot;
import com.rheinmetal.tianshu.protocol.observability.ProtocolLaneSnapshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ProtocolExecutorManager implements AutoCloseable {
    private final MainThreadExecutor mainThreadExecutor;
    private final Map<ExecutionLane, ThreadPoolExecutor> executors = new EnumMap<>(ExecutionLane.class);
    private final Map<String, TaskGroup> taskGroups = new java.util.concurrent.ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor scheduledExecutor;

    public ProtocolExecutorManager(MainThreadExecutor mainThreadExecutor) {
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        executors.put(ExecutionLane.CPU, fixedExecutor(ExecutionLane.CPU, Math.max(1, processors / 2), 64));
        executors.put(ExecutionLane.IO, fixedExecutor(ExecutionLane.IO, Math.max(2, Math.min(4, processors / 2)), 64));
        executors.put(ExecutionLane.AUDIO_IO, fixedExecutor(ExecutionLane.AUDIO_IO, 1, 128));
        executors.put(ExecutionLane.TTS_FAST, fixedExecutor(ExecutionLane.TTS_FAST, 1, 4));
        executors.put(ExecutionLane.TTS_AUTOREGRESSIVE, fixedExecutor(ExecutionLane.TTS_AUTOREGRESSIVE, 1, 1));
        executors.put(ExecutionLane.ASR_STREAM, fixedExecutor(ExecutionLane.ASR_STREAM, 1, 8));
        executors.put(ExecutionLane.MODEL_LOAD, fixedExecutor(ExecutionLane.MODEL_LOAD, 1, 2));
        executors.put(ExecutionLane.LONG, fixedExecutor(ExecutionLane.LONG, 2, 8));
        this.scheduledExecutor = new ScheduledThreadPoolExecutor(1, threadFactory(ExecutionLane.SCHEDULED));
        this.scheduledExecutor.setRemoveOnCancelPolicy(true);
    }

    public ProtocolTaskHandle submit(ProtocolTaskSpec spec, Runnable task) {
        Objects.requireNonNull(task, "task");
        return submit(spec, () -> {
            task.run();
            return null;
        });
    }

    public <T> ProtocolTaskHandle submit(ProtocolTaskSpec spec, Callable<T> task) {
        ProtocolTaskSpec effective = Objects.requireNonNull(spec, "spec");
        Callable<T> effectiveTask = Objects.requireNonNull(task, "task");
        ManagedTaskHandle handle = new ManagedTaskHandle(effective);
        if (effective.lane() == ExecutionLane.MAIN) {
            handle.setState(ProtocolTaskState.QUEUED);
            mainThreadExecutor.execute(() -> runManaged(handle, effectiveTask, null));
            return handle;
        }
        if (effective.lane() == ExecutionLane.SCHEDULED) {
            handle.setState(ProtocolTaskState.REJECTED);
            return handle;
        }
        TaskGroup group = taskGroups.computeIfAbsent(effective.concurrencyKey(), TaskGroup::new);
        if (!group.offer(new QueuedTask<>(handle, effectiveTask))) {
            handle.setState(ProtocolTaskState.REJECTED);
        }
        return handle;
    }

    public ProtocolTaskHandle schedule(ProtocolTaskSpec spec, Runnable task, Duration delay) {
        Objects.requireNonNull(task, "task");
        ProtocolTaskSpec effective = Objects.requireNonNull(spec, "spec");
        ManagedTaskHandle handle = new ManagedTaskHandle(effective);
        long delayMillis = Math.max(0L, delay == null ? 0L : delay.toMillis());
        Runnable wrapped = () -> runManaged(handle, () -> {
            task.run();
            return null;
        }, null);
        try {
            handle.setState(ProtocolTaskState.QUEUED);
            ScheduledFuture<?> future = scheduledExecutor.schedule(wrapped, delayMillis, TimeUnit.MILLISECONDS);
            handle.setFuture(future);
            return handle;
        } catch (RejectedExecutionException exception) {
            handle.setState(ProtocolTaskState.REJECTED);
            return handle;
        }
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
        lanes.add(new ProtocolLaneSnapshot(ExecutionLane.SCHEDULED, scheduledExecutor.getPoolSize(), scheduledExecutor.getActiveCount(), scheduledExecutor.getQueue().size(), scheduledExecutor.getCompletedTaskCount(), 0L));
        lanes.add(new ProtocolLaneSnapshot(ExecutionLane.MAIN, 0, 0, 0, 0L, 0L));
        return new ProtocolExecutorSnapshot(lanes, running, queued);
    }

    private <T> void runManaged(ManagedTaskHandle handle, Callable<T> task, Runnable onFinish) {
        if (handle.state() == ProtocolTaskState.CANCELLED) {
            if (onFinish != null) {
                onFinish.run();
            }
            return;
        }
        handle.setState(ProtocolTaskState.RUNNING);
        try {
            task.call();
            if (handle.state() != ProtocolTaskState.CANCELLED) {
                handle.setState(ProtocolTaskState.COMPLETED);
            }
        } catch (Exception exception) {
            if (handle.state() != ProtocolTaskState.CANCELLED) {
                handle.setState(ProtocolTaskState.FAILED);
            }
        } finally {
            if (onFinish != null) {
                onFinish.run();
            }
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
        private int running;

        private TaskGroup(String key) {
            this.key = key;
        }

        private boolean offer(QueuedTask<?> task) {
            synchronized (this) {
                if (queue.size() >= task.handle.spec.queueCapacity()) {
                    return false;
                }
                task.handle.setState(ProtocolTaskState.QUEUED);
                queue.offer(task);
            }
            drain();
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
                        if (task == null || running >= task.handle.spec.maxConcurrency()) {
                            break;
                        }
                        queue.poll();
                        running++;
                    }
                    submitToLane(task);
                }
            } finally {
                draining.set(false);
                boolean shouldDrainAgain;
                synchronized (this) {
                    shouldDrainAgain = !queue.isEmpty() && running < queue.peek().handle.spec.maxConcurrency();
                    if (running == 0 && queue.isEmpty()) {
                        taskGroups.remove(key, this);
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
                Future<?> future = executor.submit(() -> runManaged(task.handle, task.callable, this::finishOne));
                task.handle.setFuture(future);
            } catch (RejectedExecutionException exception) {
                task.handle.setState(ProtocolTaskState.REJECTED);
                finishOne();
            }
        }

        private void finishOne() {
            synchronized (this) {
                running = Math.max(0, running - 1);
            }
            drain();
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

    @Override
    public void close() {
        for (ThreadPoolExecutor executor : executors.values()) {
            executor.shutdown();
        }
        scheduledExecutor.shutdown();
    }

    private static final class ManagedTaskHandle implements ProtocolTaskHandle {
        private final ProtocolTaskSpec spec;
        private final AtomicReference<ProtocolTaskState> state = new AtomicReference<>(ProtocolTaskState.ACCEPTED);
        private volatile Future<?> future;

        private ManagedTaskHandle(ProtocolTaskSpec spec) {
            this.spec = spec;
        }

        private void setFuture(Future<?> future) {
            this.future = future;
        }

        private void setState(ProtocolTaskState state) {
            this.state.set(state);
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
        public boolean cancel(String reason) {
            setState(ProtocolTaskState.CANCELLED);
            Future<?> current = future;
            return current != null && current.cancel(spec.interruptible());
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
