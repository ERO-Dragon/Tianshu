package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutionPolicy;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dispatch-order contract of the queue every broker drains from.
 *
 * <p>Equal-priority work is ordered by {@link BrokerTask#sequence()}, not by the millisecond {@code createdAt}
 * header. Two envelopes created in the same millisecond compare equal on {@code createdAt}, and a
 * {@link PriorityBlockingQueue} may then hand them back in heap order rather than in enqueue order.
 * {@code AbstractQueueBroker.drain()} dispatches through {@code queue.poll()}, so the poll order asserted here
 * is the dispatch order.</p>
 */
class BrokerTaskTest {
    /** One fixed instant, so every task built below really ties on {@code createdAt}. */
    private static final long SAME_MILLISECOND = 1_700_000_000_000L;

    @Test
    void equalPriorityAndEqualCreatedAtLeaveTheQueueInEnqueueOrder() {
        try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            BoundedQueueBroker broker = broker(executors, "bounded:enqueue-order-test");
            List<BrokerTask> enqueued = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                enqueued.add(enqueue(broker, "envelope-" + index, Priority.NORMAL, SAME_MILLISECOND));
            }

            assertEquals(1, enqueued.stream().map(task -> task.envelope().header().createdAt()).distinct().count(),
                    "every task must share one millisecond, otherwise the case never hits the tie");
            for (int index = 1; index < enqueued.size(); index++) {
                assertTrue(enqueued.get(index - 1).sequence() < enqueued.get(index).sequence(),
                        "the sequence must be allocated in enqueue order");
            }
            assertEquals(enqueued, drain(broker.queue), "same priority and same millisecond must dispatch in enqueue order");
        }
    }

    @Test
    void sequenceDecidesBeforeTheCreationTimestamp() {
        try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            BoundedQueueBroker broker = broker(executors, "bounded:sequence-before-timestamp-test");
            // Enqueued first but stamped later: enqueue order is the contract, not the header timestamp.
            BrokerTask first = enqueue(broker, "envelope-first", Priority.NORMAL, SAME_MILLISECOND + 500L);
            BrokerTask second = enqueue(broker, "envelope-second", Priority.NORMAL, SAME_MILLISECOND);

            assertTrue(first.sequence() < second.sequence());
            assertTrue(first.compareTo(second) < 0, "the sequence outranks createdAt: enqueued first, dispatched first");
            assertTrue(second.compareTo(first) > 0);
        }
    }

    @Test
    void priorityStillOutranksTheSequence() {
        try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            BoundedQueueBroker broker = broker(executors, "bounded:priority-before-sequence-test");
            BrokerTask low = enqueue(broker, "envelope-low", Priority.LOW, SAME_MILLISECOND);
            BrokerTask high = enqueue(broker, "envelope-high", Priority.HIGH, SAME_MILLISECOND);

            assertTrue(low.sequence() < high.sequence(), "the high-priority task is enqueued last");
            assertEquals(List.of(high, low), drain(broker.queue), "priority still wins over enqueue order");
        }
    }

    private static BoundedQueueBroker broker(ProtocolExecutorManager executors, String brokerId) {
        return new BoundedQueueBroker(brokerId, 16, 1, executors, new ProtocolExecutionPolicy());
    }

    /**
     * Enqueues one task without dispatching it.
     *
     * <p>Only the envelope header and the allocated sequence take part in ordering, so the registration and the
     * runtime — which a real dispatch needs — stay null here.</p>
     */
    private static BrokerTask enqueue(BoundedQueueBroker broker, String envelopeId, Priority priority, long createdAt) {
        BrokerTask task = new BrokerTask(envelope(envelopeId, priority, createdAt), null, null);
        broker.enqueue(task);
        return task;
    }

    private static TianshuEnvelope envelope(String envelopeId, Priority priority, long createdAt) {
        return EnvelopeBuilder.eventTopic("test.broker.source", "test.broker.topic", PayloadType.CUSTOM, null)
                .envelopeId(envelopeId)
                .priority(priority)
                .createdAt(createdAt)
                .build();
    }

    private static List<BrokerTask> drain(PriorityBlockingQueue<BrokerTask> queue) {
        List<BrokerTask> polled = new ArrayList<>();
        BrokerTask task;
        while ((task = queue.poll()) != null) {
            polled.add(task);
        }
        return polled;
    }
}
