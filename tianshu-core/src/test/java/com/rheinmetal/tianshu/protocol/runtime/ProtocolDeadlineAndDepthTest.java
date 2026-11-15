package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.AckPolicy;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CancellationScope;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.FailurePolicy;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deadline handling and the derivation-depth guard, both of which needed a queued envelope to show up in. */
class ProtocolDeadlineAndDepthTest {

    @Test
    void anEnvelopeThatExpiresWhileQueuedIsNotRun() throws Exception {
        // A main-thread executor that holds its tasks, which is what a busy platform thread looks like from here.
        List<Runnable> held = new ArrayList<>();
        ProtocolRuntime runtime = new ProtocolRuntime(held::add, ProtocolRuntimePolicy.defaults());
        List<String> handled = new ArrayList<>();
        runtime.registerModule(mainThreadModule(), (envelope, context) -> {
            handled.add(envelope.envelopeId());
            context.complete(envelope.envelopeId());
        });

        runtime.submit(mainThreadEnvelope("expires-in-the-queue")
                .expireAt(System.currentTimeMillis() + 50L)
                .build());
        assertEquals(1, held.size(), "the envelope should be waiting on the main thread");

        // It waited too long: by the time the platform thread gets to it, its deadline is in the past.
        Thread.sleep(150L);
        held.get(0).run();

        assertTrue(handled.isEmpty(), "an expired envelope must not reach its handler: " + handled);
        assertEquals(EnvelopeStatus.EXPIRED, runtime.lifecycle().statusOf("expires-in-the-queue"));
    }

    @Test
    void aParentChainThatLoopsDoesNotHangTheSubmitter() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run, ProtocolRuntimePolicy.builder()
                .maxTraceDepth(4)
                .build());
        List<String> handled = new ArrayList<>();
        runtime.registerModule(moduleDescriptor(), (envelope, context) -> {
            handled.add(envelope.envelopeId());
            context.complete(envelope.envelopeId());
        });

        // parentId is a public setter, so an envelope can name itself as its own parent. Walking that chain used to
        // never return — on whichever thread submitted, which may be the server tick thread.
        TianshuEnvelope loop = EnvelopeBuilder.commandToCapability("test.source", "test.target",
                        PayloadType.NONE, EmptyPayload.INSTANCE)
                .envelopeId("self-parented")
                .parentId("self-parented")
                .build();

        runtime.submit(loop);

        assertTrue(handled.isEmpty(), "a self-parented envelope must be rejected, not dispatched: " + handled);
        assertEquals(1, runtime.stormGuard().rejectionSnapshot().get("TRACE_DEPTH_EXCEEDED"),
                runtime.stormGuard().rejectionSnapshot().toString());
    }

    private static ModuleDescriptor mainThreadModule() {
        return new ModuleDescriptor(
                "test.main",
                List.of(new CapabilityDescriptor(
                        "test.main.target",
                        PayloadType.NONE,
                        EmptyPayload.class,
                        BrokerType.MAIN_THREAD,
                        EnumSet.of(PacketType.COMMAND),
                        Priority.NORMAL
                )),
                ThreadPolicy.MUST_MAIN,
                CancellationScope.SELF_ONLY,
                FailurePolicy.REPORT_ONLY,
                DeliveryPolicy.WAIT_IN_QUEUE,
                true,
                false,
                1,
                8
        );
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

    private static EnvelopeBuilder mainThreadEnvelope(String envelopeId) {
        return EnvelopeBuilder.commandToCapability("test.source", "test.main.target", PayloadType.NONE,
                        EmptyPayload.INSTANCE)
                .envelopeId(envelopeId)
                .threadPolicy(ThreadPolicy.MUST_MAIN)
                // The builder defaults the deadline to thirty seconds out and the header refuses an expireAt earlier
                // than the deadline, so a short-lived envelope has to set both.
                .deadline(System.currentTimeMillis())
                .ackPolicy(AckPolicy.NONE);
    }

    private enum EmptyPayload implements ITianshuPayload {
        INSTANCE
    }
}