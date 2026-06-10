package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CancellationScope;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.FailurePolicy;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.HandlerRegistration;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolExecutionPolicyTest {
    private final ProtocolExecutionPolicy policy = new ProtocolExecutionPolicy();

    @Test
    void moduleThreadPolicyChoosesHandlerLane() {
        assertEquals(ExecutionLane.IO, policy.resolveLane(envelope(ThreadPolicy.ASYNC_WORKER), registration(ThreadPolicy.IO_BLOCKING, BrokerType.BOUNDED_QUEUE)));
        assertEquals(ExecutionLane.CPU, policy.resolveLane(envelope(ThreadPolicy.IO_BLOCKING), registration(ThreadPolicy.ASYNC_WORKER, BrokerType.BOUNDED_QUEUE)));
    }

    @Test
    void mainThreadBrokerAlwaysUsesMainLane() {
        assertEquals(ExecutionLane.MAIN, policy.resolveLane(envelope(ThreadPolicy.ANY), registration(ThreadPolicy.ANY, BrokerType.MAIN_THREAD)));
    }

    @Test
    void hardMainThreadEnvelopeUsesMainLane() {
        assertEquals(ExecutionLane.MAIN, policy.resolveLane(envelope(ThreadPolicy.MUST_MAIN), registration(ThreadPolicy.ASYNC_WORKER, BrokerType.BOUNDED_QUEUE)));
    }

    private static com.rheinmetal.tianshu.protocol.TianshuEnvelope envelope(ThreadPolicy threadPolicy) {
        return EnvelopeBuilder.create()
                .sourceId("test.source")
                .target("test.target")
                .payloadType(PayloadType.NONE)
                .threadPolicy(threadPolicy)
                .payload(EmptyPayload.INSTANCE)
                .build();
    }

    private static HandlerRegistration registration(ThreadPolicy threadPolicy, BrokerType brokerType) {
        CapabilityDescriptor capability = new CapabilityDescriptor(
                "test.target",
                PayloadType.NONE,
                EmptyPayload.class,
                brokerType,
                EnumSet.allOf(com.rheinmetal.tianshu.protocol.PacketType.class),
                Priority.LOW
        );
        ModuleDescriptor module = new ModuleDescriptor(
                "test.module",
                List.of(capability),
                threadPolicy,
                CancellationScope.SELF_ONLY,
                FailurePolicy.REPORT_ONLY,
                DeliveryPolicy.WAIT_IN_QUEUE,
                true,
                false,
                1,
                4
        );
        return new HandlerRegistration(module, capability, (envelope, context) -> {});
    }

    private enum EmptyPayload implements ITianshuPayload {
        INSTANCE
    }
}
