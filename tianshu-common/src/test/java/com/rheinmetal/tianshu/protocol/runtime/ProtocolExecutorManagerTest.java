package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.Priority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static ProtocolTaskSpec spec(String key, Priority priority, int maxConcurrency, int queueCapacity) {
        return ProtocolTaskSpec.builder()
                .moduleId("test")
                .lane(ExecutionLane.CPU)
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
}
