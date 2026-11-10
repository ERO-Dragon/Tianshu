package com.rheinmetal.tianshu.function.weaving.blackbox;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.function.weaving.LlmModule;
import com.rheinmetal.tianshu.function.weaving.LlmModuleService;
import com.rheinmetal.tianshu.function.weaving.contract.LLMCacheManagePayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMCacheManageResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.function.weaving.protocol.LlmCapabilities;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmRuntimeState;
import com.rheinmetal.tianshu.function.weaving.settings.WeavingConfiguration;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistry;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Boots the real module against a real endpoint and drives it through the protocol runtime.
 *
 * <p>Nothing here is stubbed below the protocol: the module builds its own
 * {@code OpenAiCompatibleInferenceClient}, the client opens a socket to {@link FakeOpenAiEndpoint},
 * and results arrive as ordinary response envelopes. A failure therefore means the lane itself would
 * fail for a player, not that a mock disagreed with a mock.
 */
final class BlackBoxDriver implements AutoCloseable {
    private static final AtomicInteger RESPONSE_MODULE_SEQUENCE = new AtomicInteger();

    private final LlmModule module;
    private final ProtocolRuntime runtime;
    private final LlmModuleService moduleService;
    private final ModuleRuntimeState state;
    private final FakeGameEnvironment environment;

    private BlackBoxDriver(LlmModule module, ProtocolRuntime runtime, LlmModuleService moduleService,
                           ModuleRuntimeState state, FakeGameEnvironment environment) {
        this.module = module;
        this.runtime = runtime;
        this.moduleService = moduleService;
        this.state = state;
        this.environment = environment;
    }

    static BlackBoxDriver start(WeavingConfiguration config) throws InterruptedException {
        FakeGameEnvironment environment = new FakeGameEnvironment();
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        ModuleRuntimeState state = new ModuleRuntimeState();
        LlmModule module = new LlmModule(environment, config, runtime);
        module.register(new ModuleRegistrationContext(runtime, services));
        module.prepare(new ModuleRuntimeContext(runtime, services, new TriggerRegistry(), state));
        module.start(null);

        LlmModuleService moduleService = services.require(LlmModuleService.class);
        await(() -> moduleService.snapshot().state() == LlmRuntimeState.RUNNING, 10_000L,
                () -> "the cloud lane did not start: " + moduleService.snapshot().state()
                        + " / " + moduleService.snapshot().failureMessage());
        return new BlackBoxDriver(module, runtime, moduleService, state, environment);
    }

    LlmModuleService moduleService() {
        return moduleService;
    }

    /** Everything the module logged, for a failing integration assertion. */
    java.util.List<String> logs() {
        java.util.List<String> all = new java.util.ArrayList<>();
        all.addAll(environment.infos);
        all.addAll(environment.warnings);
        all.addAll(environment.errors);
        return all;
    }

    /** Capability state as a consumer would read it before sending a request. */
    com.rheinmetal.tianshu.core.runtime.RuntimeCapabilityState capabilityState(
            com.rheinmetal.tianshu.core.runtime.RuntimeCapability capability) {
        return state.capabilities().status(capability).state();
    }

    /** Waits for a capability to settle, which is how a real embedding load reports itself. */
    void awaitCapability(com.rheinmetal.tianshu.core.runtime.RuntimeCapability capability,
                         com.rheinmetal.tianshu.core.runtime.RuntimeCapabilityState expected,
                         long timeoutMillis) throws InterruptedException {
        await(() -> capabilityState(capability) == expected, timeoutMillis,
                () -> capability + " stayed " + capabilityState(capability) + " instead of " + expected);
    }

    /** One {@code LLM.CACHE_MANAGE} call through the protocol runtime. */
    LLMCacheManageResultPayload cache(LLMCacheManagePayload payload) throws InterruptedException {
        TianshuEnvelope envelope = EnvelopeBuilder.requestCapability(
                "module.blackbox",
                LlmCapabilities.LLM_CACHE_MANAGE,
                PayloadType.of("LLM_CACHE_MANAGE"),
                payload
        ).build();
        List<LLMCacheManageResultPayload> results = capture(envelope.envelopeId(),
                "LLM_CACHE_MANAGE_RESULT", LLMCacheManageResultPayload.class);
        runtime.submit(envelope);
        await(() -> !results.isEmpty(), 10_000L,
                () -> "no cache result for action " + payload.action());
        return results.get(0);
    }

    /** One {@code LLM.PRIMITIVE_QUERY} call through the protocol runtime. */
    LLMPrimitiveResultPayload primitive(LLMPrimitiveQueryPayload payload) throws InterruptedException {
        TianshuEnvelope envelope = EnvelopeBuilder.requestCapability(
                "module.blackbox",
                LlmCapabilities.LLM_PRIMITIVE_QUERY,
                PayloadType.of("LLM_PRIMITIVE_QUERY"),
                payload
        ).build();
        List<LLMPrimitiveResultPayload> results = capture(envelope.envelopeId(),
                "LLM_PRIMITIVE_RESULT", LLMPrimitiveResultPayload.class);
        runtime.submit(envelope);
        await(() -> !results.isEmpty(), 10_000L,
                () -> "no primitive result for query " + payload.queryType());
        return results.get(0);
    }

    /**
     * Sends one prompt through {@code LLM.REQUEST} and waits for its terminal result (and, for a
     * streaming request, for the terminal chunk as well).
     */
    Response prompt(LLMPromptRequestPayload payload) throws InterruptedException {
        return await(promptAsync(payload));
    }

    /**
     * Starts one prompt and returns before its answer arrives, so a test can cancel it while the
     * provider is still streaming.
     */
    Pending promptAsync(LLMPromptRequestPayload payload) {
        TianshuEnvelope envelope = EnvelopeBuilder.requestCapability(
                "module.blackbox",
                LlmCapabilities.LLM_REQUEST,
                PayloadType.of("LLM_PROMPT_REQUEST"),
                payload
        ).build();

        List<LLMPromptResultPayload> results = capture(envelope.envelopeId(),
                "LLM_PROMPT_RESULT", LLMPromptResultPayload.class);
        List<LLMPromptStreamChunkPayload> chunks = payload.stream()
                ? capture(envelope.envelopeId(), "LLM_PROMPT_STREAM_CHUNK", LLMPromptStreamChunkPayload.class)
                : List.of();

        runtime.submit(envelope);
        return new Pending(envelope, results, chunks, payload.stream());
    }

    /** Waits for a started prompt's terminal result (and terminal chunk, when streaming). */
    Response await(Pending pending) throws InterruptedException {
        await(() -> !pending.results().isEmpty(), 15_000L,
                () -> "no terminal result for request " + pending.request().envelopeId());
        if (pending.streaming()) {
            await(() -> pending.chunks().stream().anyMatch(LLMPromptStreamChunkPayload::finished), 15_000L,
                    () -> "stream for " + pending.request().envelopeId() + " never produced a terminal chunk");
        }
        return new Response(pending.results().get(0), List.copyOf(pending.chunks()));
    }

    /** Sends the protocol's CANCEL envelope for an in-flight request. */
    void cancel(TianshuEnvelope request) {
        runtime.submit(EnvelopeBuilder.cancelEnvelope(
                "module.blackbox", request, "BLACKBOX_CANCEL", "cancelled by the black-box test").build());
    }

    /** A prompt that has been submitted but not awaited. */
    record Pending(
            TianshuEnvelope request,
            List<LLMPromptResultPayload> results,
            List<LLMPromptStreamChunkPayload> chunks,
            boolean streaming
    ) {
    }

    private <T extends com.rheinmetal.tianshu.protocol.ITianshuPayload> List<T> capture(String requestEnvelopeId, String payloadType, Class<T> type) {
        List<T> captured = new CopyOnWriteArrayList<>();
        registerResponse(requestEnvelopeId, payloadType, type, (envelope, context) -> {
            if (type.isInstance(envelope.payload())) {
                captured.add(type.cast(envelope.payload()));
            }
            context.complete(envelope.envelopeId());
        });
        return captured;
    }

    private void registerResponse(
            String requestEnvelopeId,
            String payloadType,
            Class<? extends com.rheinmetal.tianshu.protocol.ITianshuPayload> payloadClass,
            EnvelopeHandler handler
    ) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        CapabilityDescriptor capability = new CapabilityDescriptor(
                "blackbox.response." + payloadType,
                PayloadType.of(payloadType),
                payloadClass,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE
        );
        ModuleDescriptor module = new ModuleDescriptor(
                "module.blackbox.responses." + RESPONSE_MODULE_SEQUENCE.incrementAndGet(),
                List.of(),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        );
        runtime.registerResponseHandler(requestEnvelopeId, module, capability, handler);
    }

    private static void await(BooleanSupplier condition, long timeoutMillis, java.util.function.Supplier<String> failure)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError(failure.get());
        }
    }

    @Override
    public void close() {
        module.destroy();
        runtime.close();
    }

    /** One request's observations: the terminal payload and every stream chunk it produced. */
    record Response(LLMPromptResultPayload result, List<LLMPromptStreamChunkPayload> chunks) {
        List<LLMPromptStreamChunkPayload> orderedChunks() {
            return chunks.stream()
                    .sorted(java.util.Comparator.comparingInt(LLMPromptStreamChunkPayload::index))
                    .toList();
        }
    }
}
