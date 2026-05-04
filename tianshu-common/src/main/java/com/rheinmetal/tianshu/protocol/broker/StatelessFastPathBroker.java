package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.HandlerRegistration;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class StatelessFastPathBroker implements ProtocolBroker {
    private final BoundedQueueBroker delegate;

    public StatelessFastPathBroker(String brokerId) {
        this.delegate = new BoundedQueueBroker(brokerId, 1024, Runtime.getRuntime().availableProcessors());
    }

    @Override
    public BrokerSubmitResult submit(TianshuEnvelope envelope, HandlerRegistration registration, ProtocolRuntime runtime) {
        return delegate.submit(envelope, registration, runtime);
    }

    @Override
    public BrokerSnapshot snapshot() {
        return delegate.snapshot();
    }

    @Override
    public void cancel(String envelopeId, String reasonCode, String message) {
        delegate.cancel(envelopeId, reasonCode, message);
    }

    @Override
    public String brokerId() {
        return delegate.brokerId();
    }
}
