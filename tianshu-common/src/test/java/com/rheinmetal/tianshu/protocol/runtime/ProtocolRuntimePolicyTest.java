package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CancellationScope;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.FailurePolicy;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolRuntimePolicyTest {
    @Test
    void bootstrapAppliesRuntimePolicy() {
        ProtocolRuntimePolicy policy = ProtocolRuntimePolicy.builder()
                .defaultStormLimitPerSecond(1)
                .build();
        ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run, policy);
        runtime.registerModule(moduleDescriptor(), (envelope, context) -> context.complete(envelope.envelopeId()));

        runtime.submit(envelope("bootstrap-one"));
        runtime.submit(envelope("bootstrap-two"));

        assertEquals(1, runtime.stormGuard().rejectionSnapshot().get("SOURCE_RATE_LIMITED"));
    }

    @Test
    void runtimePolicyControlsStormGuardDefaults() {
        ProtocolRuntimePolicy policy = ProtocolRuntimePolicy.builder()
                .defaultStormLimitPerSecond(1)
                .build();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run, policy);
        runtime.registerModule(moduleDescriptor(), (envelope, context) -> context.complete(envelope.envelopeId()));

        runtime.submit(envelope("one"));
        runtime.submit(envelope("two"));

        assertEquals(1, runtime.stormGuard().rejectionSnapshot().get("SOURCE_RATE_LIMITED"));
    }

    private static ModuleDescriptor moduleDescriptor() {
        return new ModuleDescriptor(
                "test.module",
                List.of(new CapabilityDescriptor(
                        "test.target",
                        PayloadType.NONE,
                        EmptyPayload.class,
                        BrokerType.STATELESS_FAST_PATH,
                        EnumSet.allOf(PacketType.class),
                        Priority.NORMAL
                )),
                ThreadPolicy.ASYNC_WORKER,
                CancellationScope.SELF_ONLY,
                FailurePolicy.REPORT_ONLY,
                DeliveryPolicy.WAIT_IN_QUEUE,
                true,
                false,
                1,
                4
        );
    }

    private static com.rheinmetal.tianshu.protocol.TianshuEnvelope envelope(String taskId) {
        return EnvelopeBuilder.commandToCapability("test.source", "test.target", PayloadType.NONE, EmptyPayload.INSTANCE)
                .envelopeId(taskId)
                .build();
    }

    private enum EmptyPayload implements ITianshuPayload {
        INSTANCE
    }
}
