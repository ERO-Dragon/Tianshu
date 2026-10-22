package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.HandlerRegistration;

import java.util.Objects;

public final class ProtocolExecutionPolicy {
    public ExecutionLane resolveLane(TianshuEnvelope envelope, HandlerRegistration registration) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(registration, "registration");
        BrokerType brokerType = registration.capabilityDescriptor().requiredBrokerType();
        if (brokerType == BrokerType.MAIN_THREAD) {
            return ExecutionLane.MAIN;
        }
        ThreadPolicy envelopePolicy = envelope.header().threadPolicy();
        if (envelopePolicy == ThreadPolicy.MUST_MAIN) {
            return ExecutionLane.MAIN;
        }
        ThreadPolicy modulePolicy = registration.moduleDescriptor().defaultThreadPolicy();
        ThreadPolicy policy = modulePolicy == ThreadPolicy.ANY ? envelopePolicy : modulePolicy;
        return resolveLane(policy);
    }

    public ExecutionLane resolveLane(ThreadPolicy policy) {
        return switch (policy == null ? ThreadPolicy.ASYNC_WORKER : policy) {
            case MUST_MAIN -> ExecutionLane.MAIN;
            case IO_BLOCKING -> ExecutionLane.IO;
            case ASYNC_WORKER, ANY -> ExecutionLane.CPU;
        };
    }
}
