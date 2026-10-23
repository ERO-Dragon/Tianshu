package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextCollector;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXDynamicFact;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXDynamicFactClient;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXDynamicKnowledgeFormatter;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSystem;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXKnowledgeHit;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptOrchestrator;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXDialogueInputMapper;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXInputNormalizer;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputContext;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputMode;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputProcessor;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXReplyTextRepository;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.PresenceContextFactIds;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityAction;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityType;
import com.rheinmetal.tianshu.protocol.payload.TtsControlPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmClient;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPromptRequestBuilder;

class AXTurnOrchestratorTest {
    private static final AXRecentDialogueSystem RECENT_DIALOGUE_SYSTEM =
            new AXRecentDialogueSystem(AXMemoryWindowPolicy.fromBudget(8000, 3, 60000L));
    /** 兜底句读真实资源（zh_cn）：断言的就是玩家会听到的那一句，不是测试自己编的文案。 */
    private static final AXReplyTextRepository REPLY_TEXTS =
            new AXReplyTextRepository(AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN));
    /** delivery() 的 session/turn 拼出的本轮 presence 活动 id（与 AXTurnExecution.activityId 的拼法一致）。 */
    private static final String TURN_ACTIVITY_ID = "ax.chat.session.turn";

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
                new AXOutputProcessor(adapter, outputSettings(), chatSink, REPLY_TEXTS),
                agentRuns(),
                null
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
    void completedAssistantTurnUsesLlmCompletionTokensInRecentDialogue() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AtomicReference<TianshuEnvelope> llmRequest = new AtomicReference<>();
        registerLlmSink(runtime, llmRequest);
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        AXScope scope = writableScope();
        AXRecentDialogueSystem recentDialogueSystem = new AXRecentDialogueSystem(
                AXMemoryWindowPolicy.fromBudget(8000, 3, 60000L),
                (requestId, role, content) -> java.util.OptionalInt.of(999)
        );
        AXTurnOrchestrator orchestrator = new AXTurnOrchestrator(
                () -> scope,
                new AXDialogueInputMapper(),
                new AXInputNormalizer(),
                null,
                null,
                new AXContextCollector(null, recentDialogueSystem),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null)),
                null,
                llmClient,
                new AXSessionController(adapter),
                null,
                recentDialogueSystem,
                new AXOutputProcessor(adapter, outputSettings(), new RecordingChatSink(), REPLY_TEXTS),
                agentRuns(),
                null
        );

        orchestrator.startTurn(deliveryEnvelope(), delivery());

        await(() -> llmRequest.get() != null);
        LLMPromptRequestPayload requestPayload = (LLMPromptRequestPayload) llmRequest.get().payload();
        LLMPromptResultPayload.TokenUsagePayload usage = new LLMPromptResultPayload.TokenUsagePayload(120, 7, 3, 10, 130);
        llmClient.handleResult(
                llmRequest.get().envelopeId(),
                LLMPromptResultPayload.completed(requestPayload.requestId(), "assistant completed text", "thinking", List.of(), usage)
        );

        await(() -> recentDialogueSystem.snapshot(scope).turns().size() == 2);
        assertEquals(999, recentDialogueSystem.snapshot(scope).turns().get(0).tokenCount());
        assertEquals(7, recentDialogueSystem.snapshot(scope).turns().get(1).tokenCount());
    }

    @Test
    void cancelledAssistantPartialTurnUsesLlmCompletionTokensInRecentDialogue() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AtomicReference<TianshuEnvelope> llmRequest = new AtomicReference<>();
        registerLlmSink(runtime, llmRequest);
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        AXScope scope = writableScope();
        AXRecentDialogueSystem recentDialogueSystem = new AXRecentDialogueSystem(
                AXMemoryWindowPolicy.fromBudget(8000, 3, 60000L),
                (requestId, role, content) -> java.util.OptionalInt.of(999)
        );
        AXTurnOrchestrator orchestrator = new AXTurnOrchestrator(
                () -> scope,
                new AXDialogueInputMapper(),
                new AXInputNormalizer(),
                null,
                null,
                new AXContextCollector(null, recentDialogueSystem),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null)),
                null,
                llmClient,
                new AXSessionController(adapter),
                null,
                recentDialogueSystem,
                new AXOutputProcessor(adapter, outputSettings(), new RecordingChatSink(), REPLY_TEXTS),
                agentRuns(),
                null
        );

        orchestrator.startTurn(deliveryEnvelope(), delivery());

        await(() -> llmRequest.get() != null);
        LLMPromptRequestPayload requestPayload = (LLMPromptRequestPayload) llmRequest.get().payload();
        llmClient.handleStreamChunk(
                llmRequest.get().envelopeId(),
                LLMPromptStreamChunkPayload.chunk(requestPayload.requestId(), "partial assistant text", 0)
        );
        assertTrue(llmClient.cancelChatRequests(AXTurnCancellation.playerInterrupted("test interruption")));
        LLMPromptResultPayload.TokenUsagePayload usage = new LLMPromptResultPayload.TokenUsagePayload(120, 5, 2, 7, 127);
        llmClient.handleResult(
                llmRequest.get().envelopeId(),
                LLMPromptResultPayload.cancelled(requestPayload.requestId(), "partial assistant text", "thinking", List.of(), usage)
        );

        await(() -> recentDialogueSystem.snapshot(scope).turns().size() == 2);
        assertEquals(999, recentDialogueSystem.snapshot(scope).turns().get(0).tokenCount());
        assertEquals(5, recentDialogueSystem.snapshot(scope).turns().get(1).tokenCount());
    }

    @Test
    void interruptingThroughOrchestratorPersistsAlreadyDisplayedAssistantText() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AtomicReference<TianshuEnvelope> llmRequest = new AtomicReference<>();
        registerLlmSink(runtime, llmRequest);
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        AXScope scope = writableScope();
        AXRecentDialogueSystem recentDialogueSystem = new AXRecentDialogueSystem(
                AXMemoryWindowPolicy.fromBudget(8000, 3, 60000L),
                (requestId, role, content) -> java.util.OptionalInt.of(1)
        );
        RecordingChatSink chatSink = new RecordingChatSink();
        AXTurnOrchestrator orchestrator = new AXTurnOrchestrator(
                () -> scope,
                new AXDialogueInputMapper(),
                new AXInputNormalizer(),
                null,
                null,
                new AXContextCollector(null, recentDialogueSystem),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null)),
                null,
                llmClient,
                new AXSessionController(adapter),
                null,
                recentDialogueSystem,
                new AXOutputProcessor(adapter, outputSettings(), chatSink, REPLY_TEXTS),
                agentRuns(),
                null
        );

        orchestrator.startTurn(deliveryEnvelope(), delivery());
        await(() -> llmRequest.get() != null);
        assertTrue(recentDialogueSystem.snapshot(scope).turns().size() >= 1,
                recentDialogueSystem.snapshot(scope).turns().toString());
        LLMPromptRequestPayload request = (LLMPromptRequestPayload) llmRequest.get().payload();
        assertTrue(llmClient.handleStreamChunk(
                llmRequest.get().envelopeId(),
                LLMPromptStreamChunkPayload.chunk(request.requestId(), "already displayed", 0)
        ));
        assertEquals("already displayed", chatSink.text.toString());

        orchestrator.interruptActiveTurn(AXTurnCancellation.playerInterrupted("new IA delivery"));

        await(() -> recentDialogueSystem.snapshot(scope).turns().size() >= 2);
        var snapshot = recentDialogueSystem.snapshot(scope);
        assertTrue(snapshot.turns().size() >= 2, snapshot.turns().toString());
        assertEquals("already displayed", snapshot.turns().get(1).content());
    }

    @Test
    void newIaDeliveryStopsOnlyAxTtsSourceBeforeStartingReplacementTurn() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AtomicReference<TianshuEnvelope> llmRequest = new AtomicReference<>();
        AtomicReference<TtsControlPayload> ttsControl = new AtomicReference<>();
        registerLlmSink(runtime, llmRequest);
        registerTtsControlSink(runtime, ttsControl);
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
                AXContextBudget.DEFAULT,
                null,
                llmClient,
                new AXSessionController(adapter),
                null,
                RECENT_DIALOGUE_SYSTEM,
                new AXOutputProcessor(adapter, outputSettings(), new RecordingChatSink(), REPLY_TEXTS),
                null,
                null,
                true,
                agentRuns(),
                null
        );

        orchestrator.startTurn(deliveryEnvelope(), delivery());
        await(() -> llmRequest.get() != null);
        orchestrator.startTurn(deliveryEnvelope(), new DialogueDeliveryPayload(
                "session-2", "request-2", "player", "turn-2", "second delivery", "", List.of(), List.of(), List.of(),
                DialogueInteractionHints.empty(), DialogueContextSnapshot.empty("player"),
                System.currentTimeMillis(), System.currentTimeMillis() + 30_000L
        ));

        assertNotNull(ttsControl.get());
        assertEquals(TtsControlPayload.Action.STOP_SOURCE, ttsControl.get().action());
        assertEquals(AXProtocolAdapter.SOURCE_ID, ttsControl.get().targetSource());
    }

    @Test
    void publishesTurnRuntimeStatuses() {
        ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run);
        List<PresenceActivityPayload> activities = captureActivities(runtime);
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
                new AXOutputProcessor(adapter, outputSettings(), new RecordingChatSink(), REPLY_TEXTS),
                null,
                statusPublisher,
                agentRuns(),
                null
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
        await(() -> activities.size() >= 1);
        assertEquals(
                expectedActivityKeys(turnActivity(PresenceActivityType.THINKING, PresenceActivityAction.STARTED)),
                observedActivityKeys(activities));
        assertStatusKey(runtime, AXTurnStatusPublisher.TYPE_TURN_ACCEPTED, AXTurnStatusPublisher.KEY_TURN_ACCEPTED);
        assertStatusKey(runtime, AXTurnStatusPublisher.TYPE_TURN_PROCESSING, AXTurnStatusPublisher.KEY_TURN_PROCESSING);
        assertStatusKey(runtime, AXTurnStatusPublisher.TYPE_LLM_THINKING, AXTurnStatusPublisher.KEY_LLM_THINKING);

        LLMPromptRequestPayload requestPayload = (LLMPromptRequestPayload) llmRequest.get().payload();
        llmClient.handleStreamChunk(
                llmRequest.get().envelopeId(),
                LLMPromptStreamChunkPayload.chunk(requestPayload.requestId(), "Streaming response.", 0)
        );

        await(() -> activities.size() >= 3);
        assertEquals(
                expectedActivityKeys(
                        turnActivity(PresenceActivityType.THINKING, PresenceActivityAction.STARTED),
                        turnActivity(PresenceActivityType.THINKING, PresenceActivityAction.ENDED),
                        turnActivity(PresenceActivityType.RESPONDING, PresenceActivityAction.STARTED)),
                observedActivityKeys(activities));

        llmClient.handleResult(
                llmRequest.get().envelopeId(),
                LLMPromptResultPayload.completed(requestPayload.requestId(), "Streaming response.")
        );
        await(() -> activities.size() >= 4);
        assertEquals(
                expectedActivityKeys(
                        turnActivity(PresenceActivityType.THINKING, PresenceActivityAction.STARTED),
                        turnActivity(PresenceActivityType.THINKING, PresenceActivityAction.ENDED),
                        turnActivity(PresenceActivityType.RESPONDING, PresenceActivityAction.STARTED),
                        turnActivity(PresenceActivityType.RESPONDING, PresenceActivityAction.ENDED)),
                observedActivityKeys(activities));

        ModuleStatus responding = assertStatusKey(runtime, AXTurnStatusPublisher.TYPE_RESPONDING, AXTurnStatusPublisher.KEY_RESPONDING);
        assertEquals("responding", responding.tags().get("axPipelineStage"));
        assertFalse(responding.tags().containsKey("presenceStatusType"));
        assertEquals("true", responding.tags().get("axReplying"));
        assertEquals("true", responding.tags().get("axInterruptible"));
    }

    @Test
    void rejectsNewIaDeliveryWhenInterruptionIsDisabled() {
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
                null,
                new AXContextCollector(null, RECENT_DIALOGUE_SYSTEM),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null)),
                AXContextBudget.DEFAULT,
                null,
                llmClient,
                new AXSessionController(adapter),
                null,
                RECENT_DIALOGUE_SYSTEM,
                new AXOutputProcessor(adapter, outputSettings(), new RecordingChatSink(), REPLY_TEXTS),
                null,
                null,
                false,
                agentRuns(),
                null
        );

        orchestrator.startTurn(deliveryEnvelope(), delivery());
        await(() -> llmRequest.get() != null);
        TianshuEnvelope firstRequest = llmRequest.get();

        orchestrator.startTurn(deliveryEnvelope(), new DialogueDeliveryPayload(
                "session", "request-2", "player", "turn-2", "second delivery", "", List.of(), List.of(), List.of(),
                DialogueInteractionHints.empty(), DialogueContextSnapshot.empty("player"),
                System.currentTimeMillis(), System.currentTimeMillis() + 30_000L
        ));

        assertEquals(firstRequest, llmRequest.get());
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
    void stopClosesAdmissionAndPublishesIdleStatus() {
        ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run);
        AtomicReference<TianshuEnvelope> llmRequest = new AtomicReference<>();
        registerLlmSink(runtime, llmRequest);
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXLlmClient llmClient = new AXLlmClient(adapter);
        AXTurnOrchestrator orchestrator = statusOrchestrator(
                adapter,
                llmClient,
                new AXTurnStatusPublisher(adapter)
        );

        orchestrator.startTurn(deliveryEnvelope(), delivery());
        await(() -> llmRequest.get() != null);
        orchestrator.stop();

        ModuleStatus idle = assertStatusKey(runtime, AXTurnStatusPublisher.TYPE_TURN_IDLE, AXTurnStatusPublisher.KEY_TURN_IDLE);
        assertEquals("false", idle.tags().get("axReplying"));
        llmRequest.set(null);
        orchestrator.startTurn(deliveryEnvelope(), delivery());
        assertNull(llmRequest.get());
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
                dynamicFactClient(adapter, 2_000L),
                new AXContextCollector(null, RECENT_DIALOGUE_SYSTEM),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null)),
                null,
                llmClient,
                new AXSessionController(adapter),
                null,
                RECENT_DIALOGUE_SYSTEM,
                new AXOutputProcessor(adapter, outputSettings(), new RecordingChatSink(), REPLY_TEXTS),
                agentRuns(),
                null
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
                                PresenceContextFactIds.INTERACTION_CONTEXT,
                                List.of(),
                                "",
                                95,
                                "presence.test",
                                "minecraft:enchanting_table",
                                List.of("crosshair"),
                                System.currentTimeMillis(),
                                1_000L,
                                Map.of(
                                        "crosshairTargetTypeId", "minecraft:enchanting_table",
                                        "crosshairTargetDistance", "4.0"
                                )
                        ))
                )
        ).build());

        await(() -> llmRequest.get() != null);
        LLMPromptRequestPayload payload = (LLMPromptRequestPayload) llmRequest.get().payload();
        assertTrue(payload.chunks().stream()
                .flatMap(chunk -> chunk.messageContent().stream())
                .anyMatch(message -> message.content().contains("<game_context>")
                        && message.content().contains("enchanting table")));
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
                dynamicFactClient(adapter, 2_000L),
                new AXContextCollector(null, RECENT_DIALOGUE_SYSTEM),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null)),
                null,
                llmClient,
                new AXSessionController(adapter),
                null,
                RECENT_DIALOGUE_SYSTEM,
                new AXOutputProcessor(adapter, outputSettings(), new RecordingChatSink(), REPLY_TEXTS),
                agentRuns(),
                null
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
                dynamicFactClient(adapter, 2_000L),
                new AXContextCollector(null),
                new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null)),
                null,
                llmClient,
                new AXSessionController(adapter),
                null,
                RECENT_DIALOGUE_SYSTEM,
                new AXOutputProcessor(adapter, outputSettings(), new RecordingChatSink(), REPLY_TEXTS),
                agentRuns(),
                null
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

    // ------------------------------------------------------------ 输出契约（设计 §4.1）

    @Test
    void aReplyWithoutTagsIsSpokenExactlyAsWritten() {
        ContractFixture fixture = new ContractFixture();

        fixture.answer("铁砧可以修复工具。");

        // The history turn and the spoken line are published on different lanes, so waiting for one
        // of them is not enough: the speech has to have arrived before it can be compared.
        await(() -> fixture.recent.snapshot(fixture.scope).turns().size() == 2 && !fixture.spoken.isEmpty());
        assertEquals(List.of("铁砧可以修复工具。"), List.copyOf(fixture.spoken), "平时就是人话，零格式");
        assertEquals("铁砧可以修复工具。", fixture.chatSink.text.toString());
        assertEquals("铁砧可以修复工具。", fixture.recent.snapshot(fixture.scope).turns().get(1).content());
        assertTrue(fixture.emptyAnswerDiagnostics().isEmpty(), "正常答句不是退化路径");
    }

    @Test
    void bracedProseStaysAPlainAnswer() {
        ContractFixture fixture = new ContractFixture();

        fixture.answer("{我在想这句话}");

        // The history turn and the spoken line are published on different lanes, so waiting for one
        // of them is not enough: the speech has to have arrived before it can be compared.
        await(() -> fixture.recent.snapshot(fixture.scope).turns().size() == 2 && !fixture.spoken.isEmpty());
        assertEquals(List.of("{我在想这句话}"), List.copyOf(fixture.spoken), "带花括号的普通文本原样念");
        assertEquals("{我在想这句话}", fixture.recent.snapshot(fixture.scope).turns().get(1).content());
        assertTrue(fixture.emptyAnswerDiagnostics().isEmpty());
    }

    @Test
    void delegateTagNeverReachesThePlayerAndOnlyTheSentenceBeforeItIsSpoken() {
        ContractFixture fixture = new ContractFixture();

        fixture.answer("这个问题让我去查一下。<delegate>检索如何进入暮色森林</delegate>");

        // The history turn and the spoken line are published on different lanes, so waiting for one
        // of them is not enough: the speech has to have arrived before it can be compared.
        await(() -> fixture.recent.snapshot(fixture.scope).turns().size() == 2 && !fixture.spoken.isEmpty());
        assertEquals(List.of("这个问题让我去查一下。"), List.copyOf(fixture.spoken), "标签内容绝不念出来");
        assertEquals("这个问题让我去查一下。", fixture.chatSink.text.toString(), "标签也不进聊天 UI");
        assertEquals("这个问题让我去查一下。", fixture.recent.snapshot(fixture.scope).turns().get(1).content(),
                "历史里存的是玩家听到的那句话");
        assertTrue(fixture.emptyAnswerDiagnostics().isEmpty(), "挂了标签不是退化路径");
    }

    @Test
    void tagFragmentsAreHeldBackWhileStreamingAndNeverSpoken() {
        ContractFixture fixture = new ContractFixture();

        fixture.stream("这个问题让我去查一下。<del", 0);
        fixture.stream("egate>检索如何进入暮色森林</del", 1);
        fixture.stream("egate>", 2);

        // 聊天 UI 是同步观测点：分片进没进输出层，这里立刻就能看出来。
        assertEquals("这个问题让我去查一下。", fixture.chatSink.text.toString(),
                "标签分片一个字都不能进输出层");
        await(() -> !fixture.spoken.isEmpty());
        assertEquals("这个问题让我去查一下。", String.join("", fixture.spoken), "标签分片也不能念出去");

        fixture.answer("这个问题让我去查一下。<delegate>检索如何进入暮色森林</delegate>");

        // The history turn and the spoken line are published on different lanes, so waiting for one
        // of them is not enough: the speech has to have arrived before it can be compared.
        await(() -> fixture.recent.snapshot(fixture.scope).turns().size() == 2 && !fixture.spoken.isEmpty());
        assertEquals("这个问题让我去查一下。", fixture.chatSink.text.toString(), "收尾不得重复输出");
        await(() -> "这个问题让我去查一下。".equals(String.join("", fixture.spoken)));
        assertEquals("这个问题让我去查一下。", String.join("", fixture.spoken), "收尾不得重复念");
    }

    @Test
    void aTagWithABlankBodyDoesNotSwallowTheSentence() {
        ContractFixture fixture = new ContractFixture();

        fixture.answer("让我想想。<delegate>   </delegate>");

        // The history turn and the spoken line are published on different lanes, so waiting for one
        // of them is not enough: the speech has to have arrived before it can be compared.
        await(() -> fixture.recent.snapshot(fixture.scope).turns().size() == 2 && !fixture.spoken.isEmpty());
        assertEquals(List.of("让我想想。"), List.copyOf(fixture.spoken), "空标签既不委派、也不留痕");
        assertEquals("让我想想。", fixture.chatSink.text.toString());
    }

    @Test
    void anUnclosedTagStillSpeaksTheRestOfTheText() {
        ContractFixture fixture = new ContractFixture();

        fixture.stream("让我查一下。<del", 0);
        fixture.stream("egate>如何进入暮色森林", 1);
        fixture.answer("让我查一下。<delegate>如何进入暮色森林");

        // The history turn and the spoken line are published on different lanes, so waiting for one
        // of them is not enough: the speech has to have arrived before it can be compared.
        await(() -> fixture.recent.snapshot(fixture.scope).turns().size() == 2 && !fixture.spoken.isEmpty());
        await(() -> fixture.spoken.size() == 2);
        assertEquals(List.of("让我查一下。", "如何进入暮色森林"), List.copyOf(fixture.spoken),
                "只有开标签：不委派，剩下的照样念");
        assertEquals("让我查一下。如何进入暮色森林", fixture.recent.snapshot(fixture.scope).turns().get(1).content());
    }

    @Test
    void anEmptyAnswerDegradesToTheResourceFallbackAndRecordsADiagnosticCode() {
        ContractFixture fixture = new ContractFixture();
        String fallback = REPLY_TEXTS.text(AXReplyTextRepository.KEY_EMPTY_ANSWER);
        assertFalse(fallback.isBlank(), "兜底句必须来自资源（ax_reply_texts.json）");

        fixture.answer("   ");

        // The history turn and the spoken line are published on different lanes, so waiting for one
        // of them is not enough: the speech has to have arrived before it can be compared.
        await(() -> fixture.recent.snapshot(fixture.scope).turns().size() == 2 && !fixture.spoken.isEmpty());
        assertEquals(List.of(fallback), List.copyOf(fixture.spoken), "空答句只能念兜底句");
        assertEquals(fallback, fixture.chatSink.text.toString());
        assertEquals(fallback, fixture.recent.snapshot(fixture.scope).turns().get(1).content());
        assertEquals(1, fixture.emptyAnswerDiagnostics().size(), "恰好一条诊断码");
        DiagnosticEvent diagnostic = fixture.emptyAnswerDiagnostics().get(0);
        assertEquals(AXTurnOrchestrator.DIAGNOSTIC_EMPTY_ANSWER, diagnostic.code());
        assertEquals(DiagnosticSeverity.WARN, diagnostic.severity());
        assertFalse(diagnostic.attributes().containsKey("reason"), "标签契约没有原因码");
    }

    private static AXOutputSettings outputSettings() {
        return () -> AXOutputMode.UI_AND_TTS;
    }

    /**
     * Runs the agent loop on its own thread — never inline.
     *
     * <p>Inline would deadlock every test here: the test thread drives the result by calling
     * {@code llmClient.handleResult(...)} after {@code startTurn} returns, while an inline run would be
     * blocked waiting for exactly that result (design §12.8). A cached pool is used rather than a
     * single-thread one because a test may abandon a turn: that run then holds its own thread until the
     * 10s loop deadline instead of queueing the next test's run behind it.</p>
     */
    private static AXAgentRunSubmitter agentRuns() {
        return (taskId, task) -> AGENT_RUNS.execute(task);
    }

    private static final ExecutorService AGENT_RUNS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "ax-agent-test-run");
        thread.setDaemon(true);
        return thread;
    });

    private static AXDynamicFactClient dynamicFactClient(AXProtocolAdapter adapter, long timeoutMillis) {
        return new AXDynamicFactClient(
                adapter,
                new AXDynamicKnowledgeFormatter(null, AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN)),
                timeoutMillis
        );
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

    private static AXScope writableScope() {
        return new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);
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
                new AXOutputProcessor(adapter, outputSettings(), new RecordingChatSink(), REPLY_TEXTS),
                null,
                statusPublisher,
                agentRuns(),
                null
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

    /**
     * 本轮一条 presence 活动的身份串：哪个 turn、哪种活动、开始还是结束。
     *
     * <p>presence 活动经协议 broker 派发，而本用例注册的 {@code AdapterDefaults.standard()} 的
     * maxConcurrency 是 CPU 数，同一批事件会被并行派发，落进观测列表的先后与发布先后无关。因此"第 n 条
     * 是谁"不是契约，能断言的只有"发了哪几条、各几条"。</p>
     */
    private static String turnActivity(PresenceActivityType type, PresenceActivityAction action) {
        return TURN_ACTIVITY_ID + "|" + type + "|" + action;
    }

    /** 期望侧同样排序后比较：排序后的列表相等即多重集相等，重复条目照样会被抓出来。 */
    private static List<String> expectedActivityKeys(String... keys) {
        return Arrays.stream(keys).sorted().toList();
    }

    private static List<String> observedActivityKeys(List<PresenceActivityPayload> activities) {
        List<PresenceActivityPayload> snapshot;
        // synchronizedList 的迭代必须持锁，否则并行追加会在断言时抛 ConcurrentModificationException。
        synchronized (activities) {
            snapshot = new ArrayList<>(activities);
        }
        return snapshot.stream()
                .map(activity -> activity.activityId() + "|" + activity.activityType() + "|" + activity.action())
                .sorted()
                .toList();
    }

    private static List<PresenceActivityPayload> captureActivities(ProtocolRuntime runtime) {
        List<PresenceActivityPayload> activities = java.util.Collections.synchronizedList(new ArrayList<>());
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.subscribeTopic(
                new ModuleDescriptor(
                        "module.presence.turn-test",
                        List.of(),
                        defaults.threadPolicy(),
                        defaults.cancellationScope(),
                        defaults.failurePolicy(),
                        defaults.deliveryPolicy(),
                        defaults.cancellable(),
                        defaults.supportsStreaming(),
                        defaults.maxConcurrency(),
                        defaults.queueCapacity()
                ),
                new TopicSubscriptionDescriptor(
                        ProtocolTopics.PRESENCE_ACTIVITY,
                        PayloadType.PRESENCE_ACTIVITY,
                        PresenceActivityPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.EVENT),
                        Priority.LOW,
                        CompletionPolicy.AUTO_COMPLETE_ON_RETURN
                ),
                (envelope, context) -> activities.add((PresenceActivityPayload) envelope.payload())
        );
        return activities;
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

    /**
     * 播报通道的测试替身：单并发收单。
     *
     * <p>"先念流式那句、再补终值差额"是播报顺序本身的产品契约，而 {@code AdapterDefaults.standard()}
     * 的 maxConcurrency 是 CPU 数，broker（{@code AbstractQueueBroker.drain}）会把这批命令并行派发，
     * 落进 {@code spoken} 的先后就不再等于发布先后。把它钉成单并发，顺序才重新成为可断言的契约
     * （与 {@code AXOutputProcessorTest} 同一个做法）。</p>
     */
    private static void registerTtsSink(ProtocolRuntime runtime, List<String> spoken) {
        AdapterDefaults defaults = AdapterDefaults.standard().withConcurrency(1, 64);
        runtime.registerModule(new ModuleDescriptor(
                "module.tts.test",
                List.of(new CapabilityDescriptor(
                        ProtocolCapabilities.TTS_SPEAK,
                        PayloadType.TTS_TEXT,
                        TtsSpeakPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.COMMAND, PacketType.STREAM_CHUNK, PacketType.STREAM_END),
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

    private static void registerTtsControlSink(
            ProtocolRuntime runtime,
            AtomicReference<TtsControlPayload> control
    ) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.tts.control.test",
                List.of(new CapabilityDescriptor(
                        ProtocolCapabilities.TTS_CONTROL,
                        PayloadType.CUSTOM,
                        TtsControlPayload.class,
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
        ), (envelope, context) -> {
            control.set((TtsControlPayload) envelope.payload());
            context.complete(envelope.envelopeId());
        });
    }

    private static void handleTts(TianshuEnvelope envelope, ProtocolContext context, List<String> spoken) {
        if (envelope.payload() instanceof TtsSpeakPayload payload && !payload.text().isBlank()) {
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

    /**
     * 一轮主对话的最小夹具：TTS 播报、聊天 UI、历史与诊断四个观测点一次接好。
     *
     * <p>契约的判据恰好是"同一段输出在这几个通道里各是什么"，因此这些用例共用一个夹具；分开写会重复
     * 四遍连线，四个通道的断言也很容易漂移。</p>
     */
    private static final class ContractFixture {
        private final AXLlmClient llmClient;
        private final AXRecentDialogueSystem recent;
        private final AXScope scope;
        private final RecordingChatSink chatSink;
        private final List<String> spoken = new ArrayList<>();
        private final List<DiagnosticEvent> diagnostics = new ArrayList<>();
        private final TianshuEnvelope requestEnvelope;

        private ContractFixture() {
            ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
            AtomicReference<TianshuEnvelope> llmRequest = new AtomicReference<>();
            registerLlmSink(runtime, llmRequest);
            registerTtsSink(runtime, spoken);
            this.chatSink = new RecordingChatSink();
            AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
            this.llmClient = new AXLlmClient(adapter);
            this.scope = writableScope();
            this.recent = new AXRecentDialogueSystem(AXMemoryWindowPolicy.fromBudget(8000, 3, 60000L));
            AXTurnOrchestrator orchestrator = new AXTurnOrchestrator(
                    () -> scope,
                    new AXDialogueInputMapper(),
                    new AXInputNormalizer(),
                    null,
                    null,
                    new AXContextCollector(null, recent),
                    new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(null, null, null)),
                    AXContextBudget.DEFAULT,
                    null,
                    llmClient,
                    new AXSessionController(adapter),
                    null,
                    recent,
                    new AXOutputProcessor(adapter, outputSettings(), chatSink, REPLY_TEXTS),
                    null,
                    null,
                    true,
                    diagnostics::add,
                    agentRuns(),
                    null
            );
            orchestrator.startTurn(deliveryEnvelope(), delivery());
            await(() -> llmRequest.get() != null);
            this.requestEnvelope = llmRequest.get();
        }

        /** 模型这一轮的最终输出（正文就是待判别的契约或纯文本）。 */
        private void answer(String resultText) {
            LLMPromptRequestPayload request = (LLMPromptRequestPayload) requestEnvelope.payload();
            llmClient.handleResult(
                    requestEnvelope.envelopeId(),
                    LLMPromptResultPayload.completed(request.requestId(), resultText));
        }

        /** 一个流式增量，返回它是否被投递给了本轮。 */
        private boolean stream(String text, int index) {
            LLMPromptRequestPayload request = (LLMPromptRequestPayload) requestEnvelope.payload();
            return llmClient.handleStreamChunk(
                    requestEnvelope.envelopeId(),
                    LLMPromptStreamChunkPayload.chunk(request.requestId(), text, index));
        }

        /** 只挑空答句退化路径：同一 sink 上还有时延埋点（DEBUG），它们不是退化。 */
        private List<DiagnosticEvent> emptyAnswerDiagnostics() {
            return diagnostics.stream()
                    .filter(event -> AXTurnOrchestrator.DIAGNOSTIC_EMPTY_ANSWER.equals(event.code()))
                    .toList();
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
