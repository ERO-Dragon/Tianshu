package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;

public final class BoundedQueueBroker extends AbstractQueueBroker {
    public BoundedQueueBroker(String brokerId, int queueCapacity, int maxConcurrency, ProtocolExecutorManager executorManager) {
        super(brokerId, queueCapacity, maxConcurrency, executorManager);
    }
}
