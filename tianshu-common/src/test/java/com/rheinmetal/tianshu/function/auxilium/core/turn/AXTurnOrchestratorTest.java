package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextCollector;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXDynamicFact;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXDynamicFactClient;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSystem;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXKnowledgeHit;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptOrchestrator;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXDialogueInputMapper;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXInputNormalizer;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputContext;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputMode;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputProcessor;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputSettings;
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
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmClient;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPromptRequestBuilder;

class AXTurnOrchestratorTest {
    private static final AXRecentDialogueSystem RECENT_DIALOGUE_SYSTEM = new AXRecentDialogueSystem(new AXMemoryWindowPolicy(
            8000,
            2000,
            2000,
            1500,
            1000,
            1000,
            25,
            40,
            25,
            40,
            28000,
            120000,
            3,
            60000L
    ));

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
                new AXContextCollector(null, RECENT_DIALOGUE_SYSTEM),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null)),
                null,
                llmClient,
                new AXSessionController(adapter),
                null,
                RECENT_DIALOGUE_SYSTEM,
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
    void publishesTurnRuntimeStatuses() {
        ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run);
        AtomicReference<TianshuEnvelope> llmRequest = new AtomicReference<>();
        registerLlmSink(runtime, llmRequest);
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXTurnStatusPublisher statusPublisher = new AXTurnStatusPublisher(adapter);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        AXTurnOrchestrator orchestrator = new AXTurnOrchestrator(
                () -> AXScope.unknown(),
                new AXDialogueInputMapper(),
                new AXInputNormalizer(),
                null,
                null,
                new AXContextCollector(null, RECENT_DIALOGUE_SYSTEM),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null)),
                AXContextBudget.DEFAULT,
                null,
                llmClient,
                new AXSessionController(adapter),
                null,
                RECENT_DIALOGUE_SYSTEM,
                new AXOutputProcessor(adapter, outputSettings(), new RecordingChatSink()),
                null,
                statusPublisher
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
        assertStatusKey(runtime, AXTurnStatusPublisher.TYPE_TURN_ACCEPTED, AXTurnStatusPublisher.KEY_TURN_ACCEPTED);
        assertStatusKey(runtime, AXTurnStatusPublisher.TYPE_TURN_PROCESSING, AXTurnStatusPublisher.KEY_TURN_PROCESSING);
        assertStatusKey(runtime, AXTurnStatusPublisher.TYPE_LLM_THINKING, AXTurnStatusPublisher.KEY_LLM_THINKING);

        LLMPromptRequestPayload requestPayload = (LLMPromptRequestPayload) llmRequest.get().payload();
        llmClient.handleStreamChunk(
                llmRequest.get().envelopeId(),
                LLMPromptStreamChunkPayload.chunk(requestPayload.requestId(), "Streaming response.", 0)
        );

        ModuleStatus responding = assertStatusKey(runtime, AXTurnStatusPublisher.TYPE_RESPONDING, AXTurnStatusPublisher.KEY_RESPONDING);
        assertEquals("responding", responding.tags().get("axPipelineStage"));
        assertEquals("SPEAKING", responding.tags().get("presenceStatusType"));
    }

    @Test
    void publishesFailureAndInterruptionStatuses() {
        ProtocolRuntime failedRuntime = ProtocolBootstrap.create(Runnable::run);
        AtomicReference<TianshuEnvelope> failedLlmRequest = new AtomicReference<>();
        registerLlmSink(failedRuntime, failedLlmRequest);
        AXProtocolAdapter failedAdapter = new AXProtocolAdapter(failedRuntime);
        AXTurnStatusPublisher failedStatusPublisher = new AXTurnStatusPublisher(failedAdapter);
        AXLlmClient failedLlmClient = new AXLlmClient(failedAdapter);
        AXTurnOrchestrator failedOrchestrator = statusOrchestrator(failedAdapter, failedLlmClient, failedStatusPublisher);

        failedOrchestrator.startTurn(deliveryEnvelope(), delivery());
        await(() -> failedLlmRequest.get() != null);
        LLMPromptRequestPayload failedRequestPayload = (LLMPromptRequestPayload) failedLlmRequest.get().payload();
        failedLlmClient.handleResult(
                failedLlmRequest.get().envelopeId(),
                LLMPromptResultPayload.failed(failedRequestPayload.requestId(), "TEST_FAILURE", "test failure")
        );

        ModuleStatus failed = assertStatusKey(failedRuntime, AXTurnStatusPublisher.TYPE_FAILED, AXTurnStatusPublisher.KEY_FAILED);
        assertEquals("failed", failed.tags().get("axPipelineStage"));
        assertEquals("llm.TEST_FAILURE", failed.tags().get("reasonCode"));

        ProtocolRuntime interruptedRuntime = ProtocolBootstrap.create(Runnable::run);
        AtomicReference<TianshuEnvelope> interruptedLlmRequest = new AtomicReference<>();
        registerLlmSink(interruptedRuntime, interruptedLlmRequest);
        AXProtocolAdapter interruptedAdapter = new AXProtocolAdapter(interruptedRuntime);
        AXTurnStatusPublisher interruptedStatusPublisher = new AXTurnStatusPublisher(interruptedAdapter);
        AXLlmClient interruptedLlmClient = new AXLlmClient(interruptedAdapter);
        AXTurnOrchestrator interruptedOrchestrator = statusOrchestrator(interruptedAdapter, interruptedLlmClient, interruptedStatusPublisher);

        interruptedOrchestrator.startTurn(deliveryEnvelope(), delivery());
        await(() -> interruptedLlmRequest.get() != null);
        assertTrue(interruptedLlmClient.cancelChatRequests(AXTurnCancellation.playerInterrupted("test interruption")));

        ModuleStatus interrupted = assertStatusKey(interruptedRuntime, AXTurnStatusPublisher.TYPE_INTERRUPTED, AXTurnStatusPublisher.KEY_INTERRUPTED);
        assertEquals("interrupted", interrupted.tags().get("axPipelineStage"));
    }

    @Test
    void dynamicFactsAreInjectedAsDynamicContentInsteadOfRagChunk() {
        AXLlmPromptRequestBuilder builder = new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null));
        AXRequest request = new AXRequest("request", "这个怎么用？", "");
        AXContextSnapshot context = new AXContextSnapshot(
                AXScope.unknown(),
                null,
                List.of(AXDynamicFact.of("玩家准星指向 minecraft:enchanting_table", 90, "test")),
                ""
        );

        LLMPromptRequestPayload payload = builder.buildChatRequest(request, context, AXContextBudget.DEFAULT);

        assertEquals(1, payload.chunks().size());
        assertTrue(payload.chunks().get(0).messageContent().stream()
                .anyMatch(message -> message.content().contains("<game_context>")
                        && message.content().contains("minecraft:enchanting_table")));
    }

    @Test
    void promptAssemblyDoesNotCreateSeparateRagChunks() {
        AXLlmPromptRequestBuilder builder = new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(
                null,
                null,
                (request, context, budget) -> List.of(AXKnowledgeHit.of(
                        "ax.static_knowledge.mock",
                        List.of("minecraft:anvil | 铁砧可以修复工具。")
                )),
                null
        ));
        AXRequest request = new AXRequest("request", "铁砧怎么用？", "");

        LLMPromptRequestPayload payload = builder.buildChatRequest(request, AXContextSnapshot.empty(), AXContextBudget.DEFAULT);

        assertEquals(1, payload.chunks().size());
        assertEquals("message", payload.chunks().get(0).type());
        assertTrue(payload.chunks().get(0).messageContent().stream()
                .anyMatch(message -> message.content().contains("minecraft:anvil")));
    }

    @Test
    void requestsDynamicFactsBeforeSubmittingLlmRequest() {
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
                new AXDynamicFactClient(adapter, 2_000L),
                new AXContextCollector(null, RECENT_DIALOGUE_SYSTEM),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null)),
                null,
                llmClient,
                new AXSessionController(adapter),
                null,
                RECENT_DIALOGUE_SYSTEM,
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
    void continuesWithoutDynamicFactsWhenNoProviderIsRegistered() {
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
                new AXDynamicFactClient(adapter, 2_000L),
                new AXContextCollector(null, RECENT_DIALOGUE_SYSTEM),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null)),
                null,
                llmClient,
                new AXSessionController(adapter),
                null,
                RECENT_DIALOGUE_SYSTEM,
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
                .noneMatch(message -> message.content().contains("动态环境")));
    }

    @Test
    void continuesWithoutDynamicFactsWhenProviderFails() {
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
                new AXDynamicFactClient(adapter, 2_000L),
                new AXContextCollector(null),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null)),
                null,
                llmClient,
                new AXSessionController(adapter),
                null,
                RECENT_DIALOGUE_SYSTEM,
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
                .noneMatch(message -> message.content().contains("动态环境")));
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

    private static TianshuEnvelope deliveryEnvelope() {
        return EnvelopeBuilder.commandToCapability(
                IaProtocolAdapter.SOURCE_ID,
                AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                PayloadType.DIALOGUE_DELIVERY,
                delivery()
        ).build();
    }

    private static AXTurnOrchestrator statusOrchestrator(
            AXProtocolAdapter adapter,
            AXLlmClient llmClient,
            AXTurnStatusPublisher statusPublisher
    ) {
        return new AXTurnOrchestrator(
                () -> AXScope.unknown(),
                new AXDialogueInputMapper(),
                new AXInputNormalizer(),
                null,
                null,
                new AXContextCollector(null, RECENT_DIALOGUE_SYSTEM),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null)),
                AXContextBudget.DEFAULT,
                null,
                llmClient,
                new AXSessionController(adapter),
                null,
                RECENT_DIALOGUE_SYSTEM,
                new AXOutputProcessor(adapter, outputSettings(), new RecordingChatSink()),
                null,
                statusPublisher
        );
    }

    private static ModuleStatus assertStatusKey(ProtocolRuntime runtime, String statusType, String messageKey) {
        ModuleStatus status = awaitStatus(runtime, statusType);
        assertNotNull(status);
        assertEquals(AXProtocolAdapter.MODULE_ID, status.moduleId());
        assertEquals(messageKey, status.messageKey());
        return status;
    }

    private static ModuleStatus awaitStatus(ProtocolRuntime runtime, String statusType) {
        long deadline = System.currentTimeMillis() + 2000L;
        ModuleStatus status;
        do {
            status = runtime.moduleStatusCache().latest(AXProtocolAdapter.MODULE_ID, statusType).orElse(null);
            if (status != null) {
                return status;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (System.currentTimeMillis() < deadline);
        return null;
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
