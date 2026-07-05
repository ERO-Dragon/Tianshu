package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManagePayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AXProtocolAdapterTest {
    @Test
    void submitsPrimitiveAndCacheRequestsThroughProtocolCenter() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AtomicReference<TianshuEnvelope> primitive = new AtomicReference<>();
        AtomicReference<TianshuEnvelope> cache = new AtomicReference<>();
        registerSink(runtime, ProtocolCapabilities.LLM_PRIMITIVE_QUERY, PayloadType.LLM_PRIMITIVE_QUERY, LLMPrimitiveQueryPayload.class, primitive);
        registerSink(runtime, ProtocolCapabilities.LLM_CACHE_MANAGE, PayloadType.LLM_CACHE_MANAGE, LLMCacheManagePayload.class, cache);
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);

        adapter.submitLlmPrimitiveQuery(adapter.buildLlmPrimitiveQuery(LLMPrimitiveQueryPayload.status("status", false)));
        adapter.submitLlmCacheManage(adapter.buildLlmCacheManage(LLMCacheManagePayload.queryUid("mc.static")));

        await(() -> primitive.get() != null && cache.get() != null);
        assertInstanceOf(LLMPrimitiveQueryPayload.class, primitive.get().payload());
        assertEquals("STATUS", ((LLMPrimitiveQueryPayload) primitive.get().payload()).queryType());
        assertInstanceOf(LLMCacheManagePayload.class, cache.get().payload());
        assertEquals("mc.static", ((LLMCacheManagePayload) cache.get().payload()).uid());
    }

    private static void registerSink(
            ProtocolRuntime runtime,
            String capability,
            PayloadType payloadType,
            Class<? extends ITianshuPayload> payloadClass,
            AtomicReference<TianshuEnvelope> sink
    ) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.test." + capability,
                List.of(new CapabilityDescriptor(
                        capability,
                        payloadType,
                        payloadClass,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
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
        ), (envelope, context) -> handle(envelope, context, sink));
    }

    private static void handle(TianshuEnvelope envelope, ProtocolContext context, AtomicReference<TianshuEnvelope> sink) {
        sink.set(envelope);
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
