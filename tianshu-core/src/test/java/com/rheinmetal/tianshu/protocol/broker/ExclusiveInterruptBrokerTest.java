package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutionPolicy;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which task a full exclusive queue gives up.
 *
 * <p>{@link ExclusiveInterruptBroker#enqueue} is exercised directly: that override owns the eviction decision,
 * while going through {@code submit} would also dispatch (max concurrency 1) and drain the queue the test wants
 * to inspect. Only the envelope header and the allocated sequence take part in the decision, so the registration
 * stays null; the runtime is real because the evicted task is cancelled through its lifecycle store.</p>
 */
class ExclusiveInterruptBrokerTest {
    /** One fixed instant for every task, so no decision can lean on the header timestamp. */
    private static final long SAME_MILLISECOND = 1_700_000_000_000L;
    private static final int QUEUE_CAPACITY = 4;

    @Test
    void fullQueueGivesUpTheOldestOfTheTiedLowestPriorityTasks() {
        try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run);
             ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run)) {
            ExclusiveInterruptBroker broker = broker(executors, QUEUE_CAPACITY);
            BrokerTask oldest = enqueueTask(broker, runtime, "envelope-low-oldest", Priority.LOW);
            BrokerTask middle = enqueueTask(broker, runtime, "envelope-low-middle", Priority.LOW);
            BrokerTask newest = enqueueTask(broker, runtime, "envelope-low-newest", Priority.LOW);
            BrokerTask normal = enqueueTask(broker, runtime, "envelope-normal", Priority.NORMAL);

            // Self-check: without several tasks sharing the lowest priority the tie is never exercised and the
            // rest of this test would prove nothing.
            assertEquals(QUEUE_CAPACITY, broker.queue.size());
            assertEquals(3, countAtLowestPriority(broker.queue), "three tasks must share the lowest priority");

            BrokerTask incoming = enqueueTask(broker, runtime, "envelope-high", Priority.HIGH);

            List<BrokerTask> remaining = List.copyOf(broker.queue);
            assertEquals(QUEUE_CAPACITY, remaining.size(), "exactly one task is given up");
            assertFalse(remaining.contains(oldest), "the oldest of the tied lowest-priority tasks must be evicted");
            assertTrue(remaining.containsAll(List.of(middle, newest, normal, incoming)), "every other task survives");
            assertEquals(EnvelopeStatus.CANCELLED,
                    runtime.lifecycle().statusOf(oldest.envelope().envelopeId()),
                    "the evicted task is cancelled explicitly, not silently dropped");
        }
    }

    @Test
    void lowerPriorityIsGivenUpNoMatterHowYoungItIs() {
        try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run);
             ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run)) {
            ExclusiveInterruptBroker broker = broker(executors, 3);
            BrokerTask oldestNormal = enqueueTask(broker, runtime, "envelope-normal-oldest", Priority.NORMAL);
            BrokerTask background = enqueueTask(broker, runtime, "envelope-background", Priority.BACKGROUND);
            BrokerTask newestNormal = enqueueTask(broker, runtime, "envelope-normal-newest", Priority.NORMAL);

            // Self-check: exactly one task holds the lowest priority, so priority — not age — decides here.
            assertEquals(1, countAtLowestPriority(broker.queue), "only the background task holds the lowest priority");

            BrokerTask incoming = enqueueTask(broker, runtime, "envelope-high", Priority.HIGH);

            List<BrokerTask> remaining = List.copyOf(broker.queue);
            assertEquals(3, remaining.size(), "exactly one task is given up");
            assertFalse(remaining.contains(background), "the lowest priority is given up even though it is not the oldest");
            assertTrue(remaining.containsAll(List.of(oldestNormal, newestNormal, incoming)));
        }
    }

    private static ExclusiveInterruptBroker broker(ProtocolExecutorManager executors, int capacity) {
        return new ExclusiveInterruptBroker("exclusive:eviction-test", capacity, executors, new ProtocolExecutionPolicy());
    }

    private static BrokerTask enqueueTask(ExclusiveInterruptBroker broker, ProtocolRuntime runtime, String envelopeId, Priority priority) {
        BrokerTask task = new BrokerTask(envelope(envelopeId, priority), null, runtime);
        broker.enqueue(task);
        return task;
    }

    private static TianshuEnvelope envelope(String envelopeId, Priority priority) {
        return EnvelopeBuilder.eventTopic("test.exclusive.source", "test.exclusive.topic", PayloadType.CUSTOM, null)
                .envelopeId(envelopeId)
                .priority(priority)
                .createdAt(SAME_MILLISECOND)
                .build();
    }

    /** How many queued tasks sit on the lowest priority present — the tie the eviction has to break. */
    private static long countAtLowestPriority(Iterable<BrokerTask> queued) {
        Priority lowest = null;
        long count = 0;
        for (BrokerTask task : queued) {
            Priority priority = task.envelope().header().priority();
            if (lowest == null || priority.weight() < lowest.weight()) {
                lowest = priority;
                count = 1;
                continue;
            }
            if (priority.weight() == lowest.weight()) {
                count++;
            }
        }
        return count;
    }
}
