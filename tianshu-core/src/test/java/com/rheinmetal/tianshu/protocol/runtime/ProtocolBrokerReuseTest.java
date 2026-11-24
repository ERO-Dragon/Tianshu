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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A conversation must not grow the broker table.
 *
 * <p>Response capabilities are named after the request that expects them, so using that name as the broker key made a
 * broker per turn: three requests left three brokers behind, and every cancel walked all of them. A broker belongs to
 * the module's lane, which is stable.</p>
 */
class ProtocolBrokerReuseTest {

    @Test
    void manyResponsesFromOneModuleShareOneBroker() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run, ProtocolRuntimePolicy.defaults());
        runtime.registerModule(module("test.responder"), (envelope, context) -> context.complete(envelope.envelopeId()));

        for (int turn = 1; turn <= 5; turn++) {
            String requestId = "turn-" + turn;
            runtime.registerResponseHandler(requestId, responseModule("test.responder"),
                    responseCapability(requestId), (envelope, context) -> context.complete(envelope.envelopeId()));
            runtime.submit(responseEnvelope(requestId, turn));
            runtime.unregisterResponseHandlers(requestId);
        }

        assertTrue(runtime.brokers().snapshots().size() <= 2,
                "the broker table must stay flat across turns, not grow per request: "
                        + runtime.brokers().snapshots().size());
    }

    private static ModuleDescriptor module(String moduleId) {
        return new ModuleDescriptor(
                moduleId,
                List.of(new CapabilityDescriptor("test.request.target", PayloadType.NONE, EmptyPayload.class,
                        BrokerType.STATELESS_FAST_PATH, EnumSet.allOf(PacketType.class), Priority.NORMAL)),
                ThreadPolicy.ASYNC_WORKER,
                CancellationScope.SELF_ONLY,
                FailurePolicy.REPORT_ONLY,
                DeliveryPolicy.WAIT_IN_QUEUE,
                true, false, 4, 8);
    }

    private static ModuleDescriptor responseModule(String moduleId) {
        return new ModuleDescriptor(
                moduleId,
                List.of(),
                ThreadPolicy.ASYNC_WORKER,
                CancellationScope.SELF_ONLY,
                FailurePolicy.REPORT_ONLY,
                DeliveryPolicy.WAIT_IN_QUEUE,
                true, false, 4, 8);
    }

    private static CapabilityDescriptor responseCapability(String requestId) {
        return new CapabilityDescriptor("response:" + requestId + ":" + PayloadType.NONE,
                PayloadType.NONE, EmptyPayload.class, BrokerType.STATELESS_FAST_PATH,
                EnumSet.allOf(PacketType.class), Priority.NORMAL);
    }

    private static com.rheinmetal.tianshu.protocol.TianshuEnvelope responseEnvelope(String requestId, int turn) {
        return EnvelopeBuilder.commandToCapability("test.caller", "test.request.target", PayloadType.NONE,
                        EmptyPayload.INSTANCE)
                .envelopeId(requestId)
                .threadPolicy(ThreadPolicy.ASYNC_WORKER)
                .build();
    }

    private enum EmptyPayload implements ITianshuPayload {
        INSTANCE
    }
}