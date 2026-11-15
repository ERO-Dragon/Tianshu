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
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a broker does when it cannot run everything: refuse, and for the exclusive one, preempt for real. */
class ProtocolBrokerPreemptionTest {

    @Test
    void preemptionInterruptsTheRunningActionInsteadOfOnlyMarkingIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run, ProtocolRuntimePolicy.defaults());
        runtime.registerModule(exclusiveModule(), (envelope, context) -> {
            started.countDown();
            // Park rather than sleep: EnvelopeHandler declares no checked exceptions, and parking still honours an
            // interrupt — which is exactly what this test wants to observe.
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(5));
            if (Thread.interrupted()) {
                interrupted.countDown();
            } else {
                context.complete(envelope.envelopeId());
            }
        });

        runtime.submit(exclusiveEnvelope("long-action", Priority.LOW));
        assertTrue(started.await(5, TimeUnit.SECONDS), "the low-priority action should be running");

        runtime.submit(exclusiveEnvelope("urgent-action", Priority.HIGH));

        assertTrue(interrupted.await(5, TimeUnit.SECONDS),
                "a preempted action must be interrupted, not left running while only its status says cancelled");
    }

    @Test
    void aFullMainThreadQueueRefusesNormalTrafficAndKeepsHighTraffic() {
        List<Runnable> held = new ArrayList<>();
        ProtocolRuntime runtime = new ProtocolRuntime(held::add, ProtocolRuntimePolicy.defaults());
        runtime.registerModule(mainThreadModule(1), (envelope, context) -> context.complete(envelope.envelopeId()));

        runtime.submit(mainThreadEnvelope("first", Priority.NORMAL));
        runtime.submit(mainThreadEnvelope("second", Priority.NORMAL));
        runtime.submit(mainThreadEnvelope("urgent", Priority.HIGH));

        assertEquals(EnvelopeStatus.REJECTED, runtime.lifecycle().statusOf("second"),
                "a full main-thread queue has to refuse something");
        assertEquals(2, held.size(), "the admitted envelopes are the first one and the high-priority one");
        assertEquals(EnvelopeStatus.QUEUED, runtime.lifecycle().statusOf("urgent"));
    }

    private static ModuleDescriptor exclusiveModule() {
        return new ModuleDescriptor(
                "test.exclusive",
                List.of(new CapabilityDescriptor(
                        "test.exclusive.target",
                        PayloadType.NONE,
                        EmptyPayload.class,
                        BrokerType.EXCLUSIVE_INTERRUPT,
                        EnumSet.of(PacketType.COMMAND),
                        Priority.LOW
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

    private static ModuleDescriptor mainThreadModule(int queueCapacity) {
        return new ModuleDescriptor(
                "test.main",
                List.of(new CapabilityDescriptor(
                        "test.main.target",
                        PayloadType.NONE,
                        EmptyPayload.class,
                        BrokerType.MAIN_THREAD,
                        EnumSet.of(PacketType.COMMAND),
                        Priority.LOW
                )),
                ThreadPolicy.MUST_MAIN,
                CancellationScope.SELF_ONLY,
                FailurePolicy.REPORT_ONLY,
                DeliveryPolicy.WAIT_IN_QUEUE,
                true,
                false,
                1,
                queueCapacity
        );
    }

    private static com.rheinmetal.tianshu.protocol.TianshuEnvelope exclusiveEnvelope(String envelopeId,
                                                                                     Priority priority) {
        return EnvelopeBuilder.commandToCapability("test.source", "test.exclusive.target", PayloadType.NONE,
                        EmptyPayload.INSTANCE)
                .envelopeId(envelopeId)
                .priority(priority)
                .ackPolicy(AckPolicy.NONE)
                .build();
    }

    private static com.rheinmetal.tianshu.protocol.TianshuEnvelope mainThreadEnvelope(String envelopeId,
                                                                                      Priority priority) {
        return EnvelopeBuilder.commandToCapability("test.source", "test.main.target", PayloadType.NONE,
                        EmptyPayload.INSTANCE)
                .envelopeId(envelopeId)
                .priority(priority)
                .threadPolicy(ThreadPolicy.MUST_MAIN)
                .ackPolicy(AckPolicy.NONE)
                .build();
    }

    private enum EmptyPayload implements ITianshuPayload {
        INSTANCE
    }
}