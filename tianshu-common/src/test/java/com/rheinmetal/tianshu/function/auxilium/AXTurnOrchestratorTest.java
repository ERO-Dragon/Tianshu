package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextCollector;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.context.AXRuntimeContextClient;
import com.rheinmetal.tianshu.function.auxilium.context.AXRuntimeContextFact;
import com.rheinmetal.tianshu.function.auxilium.context.orchestration.AXPromptOrchestrator;
import com.rheinmetal.tianshu.function.auxilium.input.AXDialogueInputMapper;
import com.rheinmetal.tianshu.function.auxilium.input.AXInputNormalizer;
import com.rheinmetal.tianshu.function.auxilium.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.output.AXOutputContext;
import com.rheinmetal.tianshu.function.auxilium.output.AXOutputMode;
import com.rheinmetal.tianshu.function.auxilium.output.AXOutputProcessor;
import com.rheinmetal.tianshu.function.auxilium.output.AXOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.function.ia.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.function.ia.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXTurnOrchestratorTest {
    @Test
    void streamsOnlyLlmReturnedTextAndAppendsFinalSuffix() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AtomicReference<TianshuEnvelope> llmRequest = new AtomicReference<>();
        List<String> spoken = new ArrayList<>();
        registerLlmSink(runtime, llmRequest);
        registerTtsSink(runtime, spoken);
        RecordingChatSink chatSink = new RecordingChatSink();
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        AXTurnOrchestrator orchestrator = new AXTurnOrchestrator(
                () -> AXScope.unknown(),
                new AXDialogueInputMapper(),
                new AXInputNormalizer(),
                null,
                null,
                new AXContextCollector(null),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null), AXContextBudget.DEFAULT),
                llmClient,
                new AXSessionController(adapter),
                null,
                new AXOutputProcessor(adapter, outputSettings(), chatSink)
        );
        DialogueDeliveryPayload delivery = delivery();
        TianshuEnvelope deliveryEnvelope = EnvelopeBuilder.commandToCapability(
                IaProtocolAdapter.SOURCE_ID,
                AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                PayloadType.DIALOGUE_DELIVERY,
                delivery
        ).build();

        orchestrator.startTurn(deliveryEnvelope, delivery);

        await(() -> llmRequest.get() != null);
        TianshuEnvelope requestEnvelope = llmRequest.get();
        assertNotNull(requestEnvelope);
        LLMPromptRequestPayload requestPayload = (LLMPromptRequestPayload) requestEnvelope.payload();
        assertTrue(requestPayload.chunks().stream()
                .flatMap(chunk -> chunk.messageContent().stream())
                .noneMatch(message -> message.content().contains("normalized text should not be used")));

        llmClient.handleStreamChunk(
                requestEnvelope.envelopeId(),
                LLMPromptStreamChunkPayload.chunk(requestPayload.requestId(), "This is the first sentence. ", 0)
        );
        llmClient.handleResult(
                requestEnvelope.envelopeId(),
                LLMPromptResultPayload.completed(requestPayload.requestId(), "This is the first sentence. This is the final suffix.")
        );

        await(() -> spoken.size() == 2);
        assertEquals("This is the first sentence. This is the final suffix.", chatSink.text.toString());
        assertEquals(List.of("This is the first sentence.", "This is the final suffix."), spoken);
    }

    @Test
    void runtimeContextIsInjectedAsMessageInsteadOfRagChunk() {
        AXLlmPromptRequestBuilder builder = new AXLlmPromptRequestBuilder(
                new AXPromptOrchestrator(null, null, null),
                AXContextBudget.DEFAULT
        );
        AXRequest request = new AXRequest("request", "这个怎么用？", "");
        AXContextSnapshot context = new AXContextSnapshot(
                AXScope.unknown(),
                null,
                List.of(AXRuntimeContextFact.of("玩家准星指向 minecraft:enchanting_table", 90, "test")),
                ""
        );

        LLMPromptRequestPayload payload = builder.buildChatRequest(request, context);

        assertEquals(1, payload.chunks().size());
        assertTrue(payload.chunks().get(0).messageContent().stream()
                .anyMatch(message -> message.content().contains("<game_context>")
                        && message.content().contains("minecraft:enchanting_table")));
    }

    @Test
    void requestsRuntimeContextBeforeSubmittingLlmRequest() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AtomicReference<TianshuEnvelope> llmRequest = new AtomicReference<>();
        AtomicReference<TianshuEnvelope> contextQuery = new AtomicReference<>();
        registerLlmSink(runtime, llmRequest);
        registerPresenceContextSink(runtime, contextQuery);
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        AXTurnOrchestrator orchestrator = new AXTurnOrchestrator(
                () -> AXScope.unknown(),
                new AXDialogueInputMapper(),
                new AXInputNormalizer(),
                null,
                new AXRuntimeContextClient(adapter, 2_000L),
                new AXContextCollector(null),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null), AXContextBudget.DEFAULT),
                llmClient,
                new AXSessionController(adapter),
                null,
                new AXOutputProcessor(adapter, outputSettings(), new RecordingChatSink())
        );
        DialogueDeliveryPayload delivery = delivery();
        TianshuEnvelope deliveryEnvelope = EnvelopeBuilder.commandToCapability(
                IaProtocolAdapter.SOURCE_ID,
                AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                PayloadType.DIALOGUE_DELIVERY,
                delivery
        ).build();

        orchestrator.startTurn(deliveryEnvelope, delivery);

        await(() -> contextQuery.get() != null);
        assertNull(llmRequest.get());
        runtime.submit(EnvelopeBuilder.responseTo(
                "module.presence.test",
                contextQuery.get(),
                PayloadType.PRESENCE_CONTEXT_SNAPSHOT,
                PresenceContextSnapshotPayload.success(
                        ((PresenceContextQueryPayload) contextQuery.get().payload()).requestId(),
                        List.of(new PresenceContextSnapshotPayload.FactPayload(
                                "crosshair",
                                "玩家准星指向 minecraft:enchanting_table",
                                95,
                                "presence.test",
                                "minecraft:enchanting_table",
                                List.of("crosshair"),
                                System.currentTimeMillis(),
                                1_000L
                        ))
                )
        ).build());

        await(() -> llmRequest.get() != null);
        LLMPromptRequestPayload payload = (LLMPromptRequestPayload) llmRequest.get().payload();
        assertTrue(payload.chunks().stream()
                .flatMap(chunk -> chunk.messageContent().stream())
                .anyMatch(message -> message.content().contains("<game_context>")
                        && message.content().contains("minecraft:enchanting_table")));
    }

    @Test
    void continuesWithoutRuntimeContextWhenNoProviderIsRegistered() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AtomicReference<TianshuEnvelope> llmRequest = new AtomicReference<>();
        registerLlmSink(runtime, llmRequest);
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        AXTurnOrchestrator orchestrator = new AXTurnOrchestrator(
                () -> AXScope.unknown(),
                new AXDialogueInputMapper(),
                new AXInputNormalizer(),
                null,
                new AXRuntimeContextClient(adapter, 2_000L),
                new AXContextCollector(null),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null), AXContextBudget.DEFAULT),
                llmClient,
                new AXSessionController(adapter),
                null,
                new AXOutputProcessor(adapter, outputSettings(), new RecordingChatSink())
        );
        DialogueDeliveryPayload delivery = delivery();
        TianshuEnvelope deliveryEnvelope = EnvelopeBuilder.commandToCapability(
                IaProtocolAdapter.SOURCE_ID,
                AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                PayloadType.DIALOGUE_DELIVERY,
                delivery
        ).build();

        orchestrator.startTurn(deliveryEnvelope, delivery);

        await(() -> llmRequest.get() != null);
        assertNotNull(llmRequest.get());
        LLMPromptRequestPayload payload = (LLMPromptRequestPayload) llmRequest.get().payload();
        assertTrue(payload.chunks().stream()
                .flatMap(chunk -> chunk.messageContent().stream())
                .noneMatch(message -> message.content().contains("<game_context>")));
    }

    @Test
    void continuesWithoutRuntimeContextWhenProviderFails() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AtomicReference<TianshuEnvelope> llmRequest = new AtomicReference<>();
        AtomicReference<TianshuEnvelope> contextQuery = new AtomicReference<>();
        registerLlmSink(runtime, llmRequest);
        registerPresenceContextSink(runtime, contextQuery);
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        AXTurnOrchestrator orchestrator = new AXTurnOrchestrator(
                () -> AXScope.unknown(),
                new AXDialogueInputMapper(),
                new AXInputNormalizer(),
                null,
                new AXRuntimeContextClient(adapter, 2_000L),
                new AXContextCollector(null),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null), AXContextBudget.DEFAULT),
                llmClient,
                new AXSessionController(adapter),
                null,
                new AXOutputProcessor(adapter, outputSettings(), new RecordingChatSink())
        );
        DialogueDeliveryPayload delivery = delivery();
        TianshuEnvelope deliveryEnvelope = EnvelopeBuilder.commandToCapability(
                IaProtocolAdapter.SOURCE_ID,
                AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                PayloadType.DIALOGUE_DELIVERY,
                delivery
        ).build();

        orchestrator.startTurn(deliveryEnvelope, delivery);

        await(() -> contextQuery.get() != null);
        assertNull(llmRequest.get());
        runtime.submit(EnvelopeBuilder.responseTo(
                "module.presence.test",
                contextQuery.get(),
                PayloadType.PRESENCE_CONTEXT_SNAPSHOT,
                PresenceContextSnapshotPayload.failed(
                        ((PresenceContextQueryPayload) contextQuery.get().payload()).requestId(),
                        "TEST_FAILURE",
                        "provider failed"
                )
        ).build());

        await(() -> llmRequest.get() != null);
        LLMPromptRequestPayload payload = (LLMPromptRequestPayload) llmRequest.get().payload();
        assertTrue(payload.chunks().stream()
                .flatMap(chunk -> chunk.messageContent().stream())
                .noneMatch(message -> message.content().contains("<game_context>")));
    }

    private static AXOutputSettings outputSettings() {
        return () -> AXOutputMode.UI_AND_TTS;
    }

    private static DialogueDeliveryPayload delivery() {
        return new DialogueDeliveryPayload(
                "session",
                "request",
                "player",
                "turn",
                "repaired player text",
                "normalized text should not be used",
                List.of(),
                List.of(),
                List.of(),
                DialogueInteractionHints.empty(),
                DialogueContextSnapshot.empty("player"),
                System.currentTimeMillis(),
                System.currentTimeMillis() + 30_000L
        );
    }

    private static void registerLlmSink(ProtocolRuntime runtime, AtomicReference<TianshuEnvelope> request) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.test",
                List.of(new CapabilityDescriptor(
                        ProtocolCapabilities.LLM_REQUEST,
                        PayloadType.LLM_PROMPT_REQUEST,
                        LLMPromptRequestPayload.class,
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
        ), (envelope, context) -> request.set(envelope));
    }

    private static void registerPresenceContextSink(ProtocolRuntime runtime, AtomicReference<TianshuEnvelope> request) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.presence.test",
                List.of(new CapabilityDescriptor(
                        ProtocolCapabilities.PRESENCE_QUERY_CONTEXT,
                        PayloadType.PRESENCE_CONTEXT_QUERY,
                        PresenceContextQueryPayload.class,
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
        ), (envelope, context) -> request.set(envelope));
    }

    private static void registerTtsSink(ProtocolRuntime runtime, List<String> spoken) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.tts.test",
                List.of(new CapabilityDescriptor(
                        ProtocolCapabilities.TTS_SPEAK,
                        PayloadType.TTS_TEXT,
                        TtsSpeakPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.COMMAND),
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
        ), (envelope, context) -> handleTts(envelope, context, spoken));
    }

    private static void handleTts(TianshuEnvelope envelope, ProtocolContext context, List<String> spoken) {
        if (envelope.payload() instanceof TtsSpeakPayload payload) {
            spoken.add(payload.text());
        }
        context.complete(envelope.envelopeId());
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final class RecordingChatSink implements AXChatOutputSink {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void append(AXOutputContext context, String value) {
            text.append(value);
        }
    }
}
