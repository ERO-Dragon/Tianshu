package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.HandlerRegistration;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.ArrayList;
import java.util.Map;

public final class ExclusiveInterruptBroker extends AbstractQueueBroker {
    public ExclusiveInterruptBroker(String brokerId, int queueCapacity) {
        super(brokerId, queueCapacity, 1);
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
            BrokerTask lowest = null;
            for (BrokerTask queued : queue) {
                if (lowest == null || queued.envelope().header().priority().weight() < lowest.envelope().header().priority().weight()) {
                    lowest = queued;
                }
            }
            if (lowest != null && task.envelope().header().priority().weight() > lowest.envelope().header().priority().weight()) {
                queue.remove(lowest);
                lowest.runtime().lifecycle().transition(lowest.envelope().envelopeId(), EnvelopeStatus.CANCELLED, "RESOURCE_PREEMPTED", "Removed from exclusive queue");
            }
        }
        super.enqueue(task);
    }
}
