package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.HandlerRegistration;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public record BrokerTask(TianshuEnvelope envelope, HandlerRegistration registration, ProtocolRuntime runtime) implements Comparable<BrokerTask> {
    @Override
    public int compareTo(BrokerTask other) {
        int priorityCompare = Integer.compare(other.envelope.header().priority().weight(), envelope.header().priority().weight());
        if (priorityCompare != 0) return priorityCompare;
        return Long.compare(envelope.header().createdAt(), other.envelope.header().createdAt());
    }
}
