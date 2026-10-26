package com.rheinmetal.tianshu.function.ia.runtime;

import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerPreviewRefreshCoordinatorTest {
    private static final Duration INTERVAL = Duration.ofMillis(500L);

    @Test
    void repeatedStartMaintainsOnlyOneScheduledChain() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger refreshes = new AtomicInteger();
        OwnerPreviewRefreshCoordinator coordinator = new OwnerPreviewRefreshCoordinator(
                scheduler,
                INTERVAL,
                refreshes::incrementAndGet
        );

        coordinator.start();
        coordinator.start();

        assertEquals(1, scheduler.scheduledCount());
        scheduler.run(0);
        assertEquals(1, refreshes.get());
        assertEquals(2, scheduler.scheduledCount());
    }

    @Test
    void stoppedGenerationCannotRenewItselfAfterRestart() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger refreshes = new AtomicInteger();
        OwnerPreviewRefreshCoordinator coordinator = new OwnerPreviewRefreshCoordinator(
                scheduler,
                INTERVAL,
                refreshes::incrementAndGet
        );

        coordinator.start();
        ManualTask staleTask = scheduler.task(0);
        coordinator.stop();
        coordinator.start();

        staleTask.runEvenIfCancelled();

        assertEquals(0, refreshes.get());
        assertEquals(2, scheduler.scheduledCount());
        scheduler.run(1);
        assertEquals(1, refreshes.get());
        assertEquals(3, scheduler.scheduledCount());
    }

    @Test
    void stopDuringRefreshPreventsSelfRenewal() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicReference<OwnerPreviewRefreshCoordinator> coordinatorRef = new AtomicReference<>();
        OwnerPreviewRefreshCoordinator coordinator = new OwnerPreviewRefreshCoordinator(
                scheduler,
                INTERVAL,
                () -> coordinatorRef.get().stop()
        );
        coordinatorRef.set(coordinator);

        coordinator.start();
        scheduler.run(0);

        assertEquals(1, scheduler.scheduledCount());
        coordinator.start();
        assertEquals(2, scheduler.scheduledCount());
    }

    @Test
    void stopIsIdempotentAfterSchedulerRejectsTask() {
        ManualScheduler scheduler = new ManualScheduler();
        scheduler.rejectNewTasks();
        OwnerPreviewRefreshCoordinator coordinator = new OwnerPreviewRefreshCoordinator(
                scheduler,
                INTERVAL,
                () -> {
                }
        );

        coordinator.start();
        coordinator.stop();
        coordinator.stop();

        assertEquals(1, scheduler.scheduledCount());
        assertTrue(scheduler.task(0).handle().isDone());
        coordinator.start();
        assertEquals(2, scheduler.scheduledCount());
    }

    @Test
    void stopWaitsForInFlightRefreshBeforeReturning() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        CountDownLatch stopReturned = new CountDownLatch(1);
        OwnerPreviewRefreshCoordinator coordinator = new OwnerPreviewRefreshCoordinator(
                scheduler,
                INTERVAL,
                () -> {
                    refreshStarted.countDown();
                    await(releaseRefresh);
                }
        );
        coordinator.start();
        Thread refreshThread = new Thread(() -> scheduler.run(0), "ia-refresh-test");
        refreshThread.start();
        assertTrue(refreshStarted.await(1, TimeUnit.SECONDS));

        Thread stopThread = new Thread(() -> {
            coordinator.stop();
            stopReturned.countDown();
        }, "ia-stop-test");
        stopThread.start();

        assertFalse(stopReturned.await(100, TimeUnit.MILLISECONDS));
        releaseRefresh.countDown();
        assertTrue(stopReturned.await(1, TimeUnit.SECONDS));
        refreshThread.join(1_000L);
        stopThread.join(1_000L);
        assertFalse(refreshThread.isAlive());
        assertFalse(stopThread.isAlive());
    }

    @Test
    void refreshFailureEndsCurrentChainAndAllowsExplicitRestart() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger attempts = new AtomicInteger();
        OwnerPreviewRefreshCoordinator coordinator = new OwnerPreviewRefreshCoordinator(
                scheduler,
                INTERVAL,
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("test refresh failure");
                    }
                }
        );

        coordinator.start();
        assertThrows(IllegalStateException.class, () -> scheduler.run(0));

        coordinator.start();
        scheduler.run(1);
        assertEquals(2, attempts.get());
        assertEquals(3, scheduler.scheduledCount());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting in test refresh", exception);
        }
    }

    private static final class ManualScheduler implements OwnerPreviewRefreshCoordinator.DelayedScheduler {
        private final List<ManualTask> tasks = new ArrayList<>();
        private boolean rejectNewTasks;

        @Override
        public ProtocolTaskHandle schedule(Runnable task, Duration delay) {
            ManualHandle handle = new ManualHandle(rejectNewTasks ? ProtocolTaskState.REJECTED : ProtocolTaskState.QUEUED);
            tasks.add(new ManualTask(task, delay, handle));
            return handle;
        }

        private void rejectNewTasks() {
            rejectNewTasks = true;
        }

        private int scheduledCount() {
            return tasks.size();
        }

        private ManualTask task(int index) {
            return tasks.get(index);
        }

        private void run(int index) {
            task(index).run();
        }
    }

    private record ManualTask(Runnable runnable, Duration delay, ManualHandle handle) {
        private void run() {
            assertEquals(INTERVAL, delay);
            if (handle.state() == ProtocolTaskState.CANCELLED || handle.state() == ProtocolTaskState.REJECTED) {
                return;
            }
            handle.state(ProtocolTaskState.RUNNING);
            runnable.run();
            if (handle.state() != ProtocolTaskState.CANCELLED) {
                handle.state(ProtocolTaskState.COMPLETED);
            }
        }

        private void runEvenIfCancelled() {
            runnable.run();
        }
    }

    private static final class ManualHandle implements ProtocolTaskHandle {
        private ProtocolTaskState state;

        private ManualHandle(ProtocolTaskState state) {
            this.state = state;
        }

        private void state(ProtocolTaskState state) {
            this.state = state;
        }

        @Override
        public String taskId() {
            return "ia.owner-preview-refresh";
        }

        @Override
        public String moduleId() {
            return "module.ia";
        }

        @Override
        public String envelopeId() {
            return "";
        }

        @Override
        public ExecutionLane lane() {
            return ExecutionLane.SCHEDULED;
        }

        @Override
        public ProtocolTaskState state() {
            return state;
        }

        @Override
        public Optional<Throwable> failureCause() {
            return Optional.empty();
        }

        @Override
        public boolean cancel(String reason) {
            state = ProtocolTaskState.CANCELLED;
            return true;
        }

        @Override
        public boolean isDone() {
            return state == ProtocolTaskState.COMPLETED
                    || state == ProtocolTaskState.FAILED
                    || state == ProtocolTaskState.CANCELLED
                    || state == ProtocolTaskState.REJECTED;
        }

        @Override
        public boolean isRunning() {
            return state == ProtocolTaskState.RUNNING;
        }
    }
}
