package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

public final class ParallelLimitBroker extends AbstractQueueBroker {
    public ParallelLimitBroker(String brokerId, int queueCapacity, int maxConcurrency) {
        super(brokerId, queueCapacity, maxConcurrency);
    }

    @Override
    protected boolean shouldRejectWhenFull(TianshuEnvelope envelope) {
        return envelope.header().priority().weight() < 400;
    }
}
