package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.HandlerRegistration;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.List;

public final class LatestOnlyBroker extends AbstractQueueBroker {
    public LatestOnlyBroker(String brokerId) {
        super(brokerId, 1, 1);
    }

    @Override
    public BrokerSubmitResult submit(TianshuEnvelope envelope, HandlerRegistration registration, ProtocolRuntime runtime) {
        List<BrokerTask> removed = drainQueue();
        for (BrokerTask task : removed) {
            runtime.lifecycle().transition(task.envelope().envelopeId(), EnvelopeStatus.CANCELLED, "LATEST_ONLY_REPLACED", "Replaced by latest envelope");
        }
        return super.submit(envelope, registration, runtime);
    }

    @Override
    protected boolean shouldRejectWhenFull(TianshuEnvelope envelope) {
        return false;
    }
}
