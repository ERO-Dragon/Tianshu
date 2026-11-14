package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.HandlerRegistration;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutionPolicy;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.ArrayList;
import java.util.Map;

public final class ExclusiveInterruptBroker extends AbstractQueueBroker {
    public ExclusiveInterruptBroker(String brokerId, int queueCapacity, ProtocolExecutorManager executorManager, ProtocolExecutionPolicy executionPolicy) {
        super(brokerId, queueCapacity, 1, executorManager, executionPolicy);
    }

    @Override
    public BrokerSubmitResult submit(TianshuEnvelope envelope, HandlerRegistration registration, ProtocolRuntime runtime) {
        for (Map.Entry<String, BrokerTask> entry : new ArrayList<>(running.entrySet())) {
            TianshuEnvelope runningEnvelope = entry.getValue().envelope();
            if (envelope.header().priority().weight() > runningEnvelope.header().priority().weight()) {
                runtime.cancellation().cancelSelf(runningEnvelope.envelopeId(), "RESOURCE_PREEMPTED", "Preempted by higher priority envelope");
            }
        }
        return super.submit(envelope, registration, runtime);
    }

    @Override
    protected boolean shouldRejectWhenFull(TianshuEnvelope envelope) {
        return envelope.header().priority().weight() < 300;
    }

    @Override
    protected void enqueue(BrokerTask task) {
        if (queue.size() >= queueCapacity) {
            BrokerTask lowest = oldestOfLowestPriority();
            if (lowest != null && task.envelope().header().priority().weight() > lowest.envelope().header().priority().weight()) {
                queue.remove(lowest);
                lowest.runtime().lifecycle().transition(lowest.envelope().envelopeId(), EnvelopeStatus.CANCELLED, "RESOURCE_PREEMPTED", "Removed from exclusive queue");
            }
        }
        super.enqueue(task);
    }

    /**
     * The task a full queue gives up: the lowest priority, and among equal priorities the oldest enqueue.
     *
     * <p>Scanning for the minimum instead of trusting the iteration order keeps the choice deterministic — a
     * {@link java.util.PriorityBlockingQueue} iterates in heap order, not in comparator order, so "the first
     * lowest one encountered" is a placement detail rather than a decision. Among equal priorities the oldest
     * enqueue goes first: newer work describes the player's current state, and dropping the newer task instead
     * would keep stale information alive.</p>
     */
    private BrokerTask oldestOfLowestPriority() {
        BrokerTask candidate = null;
        for (BrokerTask queued : queue) {
            if (candidate == null || ranksBelow(queued, candidate)) {
                candidate = queued;
            }
        }
        return candidate;
    }

    /** Lower priority goes first; at equal priority the smaller sequence — the earlier enqueue — goes first. */
    private static boolean ranksBelow(BrokerTask task, BrokerTask other) {
        int priorityCompare = Integer.compare(
                task.envelope().header().priority().weight(),
                other.envelope().header().priority().weight()
        );
        if (priorityCompare != 0) {
            return priorityCompare < 0;
        }
        return task.sequence() < other.sequence();
    }
}
