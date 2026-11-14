package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.Priority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolExecutorManagerTest {
    private final ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run);

    @AfterEach
    void closeExecutor() {
        executors.close();
    }

    @Test
    void concurrencyKeyLimitsRunningTasksAndQueuesRemainder() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());

        executors.submit(spec("shared", Priority.NORMAL, 1, 2), () -> {
            events.add("first");
            firstStarted.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        });
        executors.submit(spec("shared", Priority.NORMAL, 1, 2), () -> events.add("second"));

        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(List.of("first"), List.copyOf(events));

        release.countDown();
        awaitEvents(events, 2);
        assertEquals(List.of("first", "second"), List.copyOf(events));
    }

    @Test
    void queueCapacityRejectsOverflowWithinConcurrencyGroup() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ProtocolTaskHandle running = executors.submit(spec("limited", Priority.NORMAL, 1, 1), () -> {
            firstStarted.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        });
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        ProtocolTaskHandle queued = executors.submit(spec("limited", Priority.NORMAL, 1, 1), () -> {});
        ProtocolTaskHandle overflow = executors.submit(spec("limited", Priority.NORMAL, 1, 1), () -> {});

        assertEquals(ProtocolTaskState.RUNNING, running.state());
        assertEquals(ProtocolTaskState.QUEUED, queued.state());
        assertEquals(ProtocolTaskState.REJECTED, overflow.state());
        release.countDown();
    }

    @Test
    void queuedTasksRunByPriorityThenFifo() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());

        executors.submit(spec("priority", Priority.NORMAL, 1, 4), () -> {
            events.add("running");
            firstStarted.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        });
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        executors.submit(spec("priority", Priority.LOW, 1, 4), () -> events.add("low"));
        executors.submit(spec("priority", Priority.HIGH, 1, 4), () -> events.add("high"));
        executors.submit(spec("priority", Priority.NORMAL, 1, 4), () -> events.add("normal"));

        release.countDown();
        awaitEvents(events, 4);
        assertEquals(List.of("running", "high", "normal", "low"), List.copyOf(events));
    }

    @Test
    void failedTaskRetainsFailureCause() throws Exception {
        RuntimeException failure = new RuntimeException("boom");

        ProtocolTaskHandle handle = executors.submit(spec("failure-cause", Priority.NORMAL, 1, 1), () -> {
            throw failure;
        });

        awaitState(handle, ProtocolTaskState.FAILED);
        Optional<Throwable> cause = handle.failureCause();
        assertTrue(cause.isPresent());
        assertSame(failure, cause.get());
    }

    @Test
    void executorPolicyControlsLaneCapacity() throws Exception {
        ProtocolExecutorPolicy policy = ProtocolExecutorPolicy.builder()
                .lane(ExecutionLane.CPU, 1, 1)
                .build();
        try (ProtocolExecutorManager customExecutors = new ProtocolExecutorManager(Runnable::run, policy)) {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            ProtocolTaskHandle running = customExecutors.submit(spec("executor-capacity", Priority.NORMAL, 3, 3), () -> {
                firstStarted.countDown();
                release.await(2, TimeUnit.SECONDS);
                return null;
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            ProtocolTaskHandle queuedInExecutor = customExecutors.submit(spec("executor-capacity", Priority.NORMAL, 3, 3), () -> {});
            ProtocolTaskHandle rejectedByExecutor = customExecutors.submit(spec("executor-capacity", Priority.NORMAL, 3, 3), () -> {});

            assertEquals(ProtocolTaskState.RUNNING, running.state());
            assertEquals(ProtocolTaskState.QUEUED, queuedInExecutor.state());
            assertEquals(ProtocolTaskState.REJECTED, rejectedByExecutor.state());
            assertTrue(rejectedByExecutor.failureCause().isPresent());

            release.countDown();
        }
    }

    @Test
    void submitAfterCloseRejectsMainLaneWithoutDispatching() {
        executors.close();
        AtomicBoolean ran = new AtomicBoolean(false);

        ProtocolTaskHandle handle = executors.submit(spec("closed-main", Priority.NORMAL, 1, 1, ExecutionLane.MAIN), () -> ran.set(true));

        assertEquals(ProtocolTaskState.REJECTED, handle.state());
        assertTrue(handle.failureCause().isPresent());
        assertEquals(false, ran.get());
    }

    @Test
    void submitToScheduledLaneIsRejectedWithDiagnosticCause() {
        ProtocolTaskHandle handle = executors.submit(spec("scheduled-submit", Priority.NORMAL, 1, 1, ExecutionLane.SCHEDULED), () -> {});

        assertEquals(ProtocolTaskState.REJECTED, handle.state());
        assertTrue(handle.failureCause().isPresent());
        assertTrue(handle.failureCause().get().getMessage().contains("schedule"));
    }

    @Test
    void cancellationDuringRunIsNotOverwrittenByCompletion() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ProtocolTaskHandle handle = executors.submit(spec("cancel-running", Priority.NORMAL, 1, 1), () -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        });

        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(handle.cancel("test_cancel"));
        release.countDown();

        awaitState(handle, ProtocolTaskState.CANCELLED);
        assertEquals(ProtocolTaskState.CANCELLED, handle.state());
    }

    @Test
    void closeRejectsScheduledTasksWithoutGrowingQueues() {
        executors.close();

        ProtocolTaskHandle handle = executors.schedule(spec("closed-scheduled", Priority.NORMAL, 1, 1, ExecutionLane.SCHEDULED), () -> {}, java.time.Duration.ofMillis(100));

        assertEquals(ProtocolTaskState.REJECTED, handle.state());
        assertTrue(handle.failureCause().isPresent());
        assertEquals(0, executors.snapshot().queuedTasks());
    }

    @Test
    void closeCancelsQueuedTasksAndClearsGroups() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ProtocolTaskHandle running = executors.submit(spec("close-group", Priority.NORMAL, 1, 4), () -> {
            firstStarted.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        });
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        ProtocolTaskHandle queued = executors.submit(spec("close-group", Priority.NORMAL, 1, 4), () -> {});

        executors.close();

        assertEquals(ProtocolTaskState.CANCELLED, queued.state());
        assertEquals(0, executors.snapshot().queuedTasks());
        release.countDown();
        awaitState(running, ProtocolTaskState.CANCELLED);
    }

    private static ProtocolTaskSpec spec(String key, Priority priority, int maxConcurrency, int queueCapacity) {
        return spec(key, priority, maxConcurrency, queueCapacity, ExecutionLane.CPU);
    }

    private static ProtocolTaskSpec spec(String key, Priority priority, int maxConcurrency, int queueCapacity, ExecutionLane lane) {
        return ProtocolTaskSpec.builder()
                .moduleId("test")
                .lane(lane)
                .concurrencyKey(key)
                .priority(priority)
                .maxConcurrency(maxConcurrency)
                .queueCapacity(queueCapacity)
                .build();
    }

    private static void awaitEvents(List<String> events, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            if (events.size() >= expected) {
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals(expected, events.size());
    }

    private static void awaitState(ProtocolTaskHandle handle, ProtocolTaskState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            if (handle.state() == expected) {
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals(expected, handle.state());
    }
}
