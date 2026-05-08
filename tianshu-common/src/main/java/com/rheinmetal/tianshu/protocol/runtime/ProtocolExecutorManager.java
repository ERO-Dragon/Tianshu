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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ProtocolExecutorManager implements AutoCloseable {
    private final MainThreadExecutor mainThreadExecutor;
    private final Map<ExecutionLane, ThreadPoolExecutor> executors = new EnumMap<>(ExecutionLane.class);
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
        Runnable wrapped = () -> runManaged(handle, effectiveTask);
        if (effective.lane() == ExecutionLane.MAIN) {
            handle.setState(ProtocolTaskState.QUEUED);
            mainThreadExecutor.execute(wrapped);
            return handle;
        }
        ThreadPoolExecutor executor = executorFor(effective.lane());
        try {
            handle.setState(ProtocolTaskState.QUEUED);
            Future<?> future = executor.submit(wrapped);
            handle.setFuture(future);
            return handle;
        } catch (RejectedExecutionException exception) {
            handle.setState(ProtocolTaskState.REJECTED);
            return handle;
        }
    }

    public ProtocolTaskHandle schedule(ProtocolTaskSpec spec, Runnable task, Duration delay) {
        Objects.requireNonNull(task, "task");
        ProtocolTaskSpec effective = Objects.requireNonNull(spec, "spec");
        ManagedTaskHandle handle = new ManagedTaskHandle(effective);
        long delayMillis = Math.max(0L, delay == null ? 0L : delay.toMillis());
        Runnable wrapped = () -> runManaged(handle, () -> {
            task.run();
            return null;
        });
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

    private <T> void runManaged(ManagedTaskHandle handle, Callable<T> task) {
        if (handle.state() == ProtocolTaskState.CANCELLED) {
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
