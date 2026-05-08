package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;

public final class ParallelLimitBroker extends AbstractQueueBroker {
    public ParallelLimitBroker(String brokerId, int queueCapacity, int maxConcurrency, ProtocolExecutorManager executorManager) {
        super(brokerId, queueCapacity, maxConcurrency, executorManager);
    }

    @Override
    protected boolean shouldRejectWhenFull(TianshuEnvelope envelope) {
        return envelope.header().priority().weight() < 400;
    }
}
