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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One request, one responder.
 *
 * <p>When two modules serve the same capability the request must still be answered: it goes to the first registration,
 * under its own envelope id, so the caller's response handler — which is keyed on that id — is the one that fires. The
 * old behaviour gave every provider a derived id and completed the original immediately, so two providers meant the
 * caller waited forever while both replies went nowhere.</p>
 */
class ProtocolSingleResponderTest {

    @Test
    void aRequestWithTwoProvidersIsAnsweredByTheFirstOneUnderTheOriginalId() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run, ProtocolRuntimePolicy.defaults());
        List<String> firstHandled = new ArrayList<>();
        List<String> secondHandled = new ArrayList<>();
        CountDownLatch firstHandledRequest = new CountDownLatch(1);

        runtime.registerModule(module("test.first"), (envelope, context) -> {
            firstHandled.add(envelope.envelopeId());
            context.complete(envelope.envelopeId());
            firstHandledRequest.countDown();
        });
        runtime.registerModule(module("test.second"), (envelope, context) -> {
            secondHandled.add(envelope.envelopeId());
            context.complete(envelope.envelopeId());
        });

        runtime.submit(command("caller-request-1"));

        try {
            assertTrue(firstHandledRequest.await(5, TimeUnit.SECONDS), "the first provider must receive the request");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting request delivery", interrupted);
        }

        assertEquals(List.of("caller-request-1"), firstHandled,
                "the first registration serves the request, under the caller's own envelope id");
        assertTrue(secondHandled.isEmpty(), "a second provider must not also run: " + secondHandled);
        assertEquals(com.rheinmetal.tianshu.protocol.EnvelopeStatus.COMPLETED,
                runtime.lifecycle().statusOf("caller-request-1"),
                "the original id is the one that ends, so the reply keyed on it finds its caller");
        assertTrue(runtime.deadLetters().snapshot(10).isEmpty(), "nothing was undeliverable");
    }

    private static ModuleDescriptor module(String moduleId) {
        return new ModuleDescriptor(
                moduleId,
                List.of(new CapabilityDescriptor(
                        "test.shared",
                        PayloadType.NONE,
                        EmptyPayload.class,
                        BrokerType.STATELESS_FAST_PATH,
                        EnumSet.allOf(PacketType.class),
                        Priority.NORMAL)),
                ThreadPolicy.ASYNC_WORKER,
                CancellationScope.SELF_ONLY,
                FailurePolicy.REPORT_ONLY,
                DeliveryPolicy.WAIT_IN_QUEUE,
                true,
                false,
                1,
                4);
    }

    private static com.rheinmetal.tianshu.protocol.TianshuEnvelope command(String envelopeId) {
        return EnvelopeBuilder.commandToCapability("test.caller", "test.shared", PayloadType.NONE,
                        EmptyPayload.INSTANCE)
                .envelopeId(envelopeId)
                .threadPolicy(ThreadPolicy.ASYNC_WORKER)
                .build();
    }

    private enum EmptyPayload implements ITianshuPayload {
        INSTANCE
    }
}
