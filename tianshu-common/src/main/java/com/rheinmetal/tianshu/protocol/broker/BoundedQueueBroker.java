package com.rheinmetal.tianshu.protocol.broker;

public final class BoundedQueueBroker extends AbstractQueueBroker {
    public BoundedQueueBroker(String brokerId, int queueCapacity, int maxConcurrency) {
        super(brokerId, queueCapacity, maxConcurrency);
    }
}
