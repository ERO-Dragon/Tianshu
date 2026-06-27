package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AXLlmPrimitiveClientTest {
    @Test
    void submitsPrimitiveQueryAndReceivesProtocolResponse() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AtomicReference<TianshuEnvelope> request = new AtomicReference<>();
        registerPrimitiveProvider(runtime, request);
        AXLlmPrimitiveClient client = new AXLlmPrimitiveClient(new AXProtocolAdapter(runtime), 2_000L);
        AtomicReference<LLMPrimitiveResultPayload> result = new AtomicReference<>();

        client.requestTokenCount("tokens", "hello", result::set);

        await(() -> request.get() != null);
        runtime.submit(EnvelopeBuilder.responseTo(
                "module.llm.test",
                request.get(),
                PayloadType.LLM_PRIMITIVE_RESULT,
                LLMPrimitiveResultPayload.tokenCount("tokens", 7)
        ).build());
        await(() -> result.get() != null);

        assertEquals(7, result.get().tokenCount());
        assertEquals(LLMPrimitiveResultPayload.STATUS_COMPLETED, result.get().status());
    }

    private static void registerPrimitiveProvider(ProtocolRuntime runtime, AtomicReference<TianshuEnvelope> request) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.test",
                List.of(new CapabilityDescriptor(
                        ProtocolCapabilities.LLM_PRIMITIVE_QUERY,
                        PayloadType.LLM_PRIMITIVE_QUERY,
                        LLMPrimitiveQueryPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.REQUEST),
                        Priority.LOW,
                        CompletionPolicy.MANUAL_COMPLETE
                )),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        ), (envelope, context) -> handle(envelope, context, request));
    }

    private static void handle(TianshuEnvelope envelope, ProtocolContext context, AtomicReference<TianshuEnvelope> request) {
        request.set(envelope);
        context.complete(envelope.envelopeId());
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
