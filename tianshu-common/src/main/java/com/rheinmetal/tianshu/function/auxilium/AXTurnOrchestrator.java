package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.context.AXContextCollector;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.context.AXRuntimeContextClient;
import com.rheinmetal.tianshu.function.auxilium.context.AXRuntimeContextFact;
import com.rheinmetal.tianshu.function.auxilium.input.AXDialogueInputMapper;
import com.rheinmetal.tianshu.function.auxilium.input.AXInputNormalizer;
import com.rheinmetal.tianshu.function.auxilium.input.AXNormalizedInput;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemoryRetrievalRequest;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemoryRetrievalResult;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemoryRetriever;
import com.rheinmetal.tianshu.function.auxilium.memory.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.output.AXOutputContext;
import com.rheinmetal.tianshu.function.auxilium.output.AXOutputProcessor;
import com.rheinmetal.tianshu.function.auxilium.runtime.AXRuntimeMaintenanceCoordinator;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeProvider;
import com.rheinmetal.tianshu.function.ia.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;

import java.util.List;
import java.util.Objects;

public final class AXTurnOrchestrator {
    private final AXScopeProvider scopeProvider;
    private final AXDialogueInputMapper dialogueInputMapper;
    private final AXInputNormalizer inputNormalizer;
    private final AXRuntimeMaintenanceCoordinator maintenanceCoordinator;
    private final AXRuntimeContextClient runtimeContextClient;
    private final AXContextCollector contextCollector;
    private final AXLlmPromptRequestBuilder llmRequestBuilder;
    private final AXContextBudget contextBudget;
    private final AXLlmClient llmClient;
    private final AXSessionController sessionController;
    private final AXMemorySystem memorySystem;
    private final AXOutputProcessor outputProcessor;
    private final AXMemoryRetriever memoryRetriever;

    public AXTurnOrchestrator(
            AXScopeProvider scopeProvider,
            AXDialogueInputMapper dialogueInputMapper,
            AXInputNormalizer inputNormalizer,
            AXRuntimeMaintenanceCoordinator maintenanceCoordinator,
            AXRuntimeContextClient runtimeContextClient,
            AXContextCollector contextCollector,
            AXLlmPromptRequestBuilder llmRequestBuilder,
            AXContextBudget contextBudget,
            AXLlmClient llmClient,
            AXSessionController sessionController,
            AXMemorySystem memorySystem,
            AXOutputProcessor outputProcessor,
            AXMemoryRetriever memoryRetriever
    ) {
        this.scopeProvider = Objects.requireNonNull(scopeProvider, "scopeProvider");
        this.dialogueInputMapper = Objects.requireNonNull(dialogueInputMapper, "dialogueInputMapper");
        this.inputNormalizer = Objects.requireNonNull(inputNormalizer, "inputNormalizer");
        this.maintenanceCoordinator = maintenanceCoordinator;
        this.runtimeContextClient = runtimeContextClient;
        this.contextCollector = Objects.requireNonNull(contextCollector, "contextCollector");
        this.llmRequestBuilder = Objects.requireNonNull(llmRequestBuilder, "llmRequestBuilder");
        this.contextBudget = contextBudget == null ? AXContextBudget.DEFAULT : contextBudget;
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.sessionController = Objects.requireNonNull(sessionController, "sessionController");
        this.memorySystem = memorySystem;
        this.outputProcessor = Objects.requireNonNull(outputProcessor, "outputProcessor");
        this.memoryRetriever = memoryRetriever;
    }

    public AXTurnOrchestrator(
            AXScopeProvider scopeProvider,
            AXDialogueInputMapper dialogueInputMapper,
            AXInputNormalizer inputNormalizer,
            AXRuntimeMaintenanceCoordinator maintenanceCoordinator,
            AXRuntimeContextClient runtimeContextClient,
            AXContextCollector contextCollector,
            AXLlmPromptRequestBuilder llmRequestBuilder,
            AXLlmClient llmClient,
            AXSessionController sessionController,
            AXMemorySystem memorySystem,
            AXOutputProcessor outputProcessor
    ) {
        this(
                scopeProvider,
                dialogueInputMapper,
                inputNormalizer,
                maintenanceCoordinator,
                runtimeContextClient,
                contextCollector,
                llmRequestBuilder,
                AXContextBudget.DEFAULT,
                llmClient,
                sessionController,
                memorySystem,
                outputProcessor,
                null
        );
    }

    public void startTurn(TianshuEnvelope deliveryEnvelope, DialogueDeliveryPayload delivery) {
        AXScope scope = currentScope();
        AXRequest rawRequest = dialogueInputMapper.map(delivery);
        AXNormalizedInput input = inputNormalizer.normalize(rawRequest);
        if (input.empty()) {
            sessionController.release(deliveryEnvelope, delivery, DialogueReleaseReason.OWNER_COMPLETED);
            return;
        }

        AXRequest request = AXRequest.fromNormalizedInput(input);
        if (maintenanceCoordinator != null) {
            maintenanceCoordinator.beforeQuestion(scope, request);
        }
        appendTurn(scope, "user", request.userText(), delivery.sessionId(), delivery.turnId());
        if (runtimeContextClient == null) {
            continueTurn(deliveryEnvelope, delivery, scope, request, List.of());
            return;
        }
        runtimeContextClient.sweepExpired();
        runtimeContextClient.request(deliveryEnvelope, delivery, scope, request, candidates ->
                continueTurn(deliveryEnvelope, delivery, scope, request, candidates));
    }

    private void continueTurn(TianshuEnvelope deliveryEnvelope, DialogueDeliveryPayload delivery, AXScope scope, AXRequest request, List<AXRuntimeContextFact> runtimeContextFacts) {
        if (memoryRetriever == null) {
            submitLlmTurn(deliveryEnvelope, delivery, scope, request, runtimeContextFacts, AXMemoryRetrievalResult.empty());
            return;
        }
        memoryRetriever.retrieve(
                new AXMemoryRetrievalRequest(scope, request, contextBudget.maxMemoryItems(), contextBudget.memoryTokenBudget()),
                memory -> submitLlmTurn(deliveryEnvelope, delivery, scope, request, runtimeContextFacts, memory)
        );
    }

    private void submitLlmTurn(TianshuEnvelope deliveryEnvelope, DialogueDeliveryPayload delivery, AXScope scope, AXRequest request, List<AXRuntimeContextFact> runtimeContextFacts, AXMemoryRetrievalResult memoryRetrieval) {
        AXContextSnapshot context = contextCollector.collect(scope, request, runtimeContextFacts, memoryRetrieval == null ? List.of() : memoryRetrieval.blocks());
        LLMPromptRequestPayload llmPayload = llmRequestBuilder.buildChatRequest(request, context)
                .withDialogueAuthorization(delivery.sessionId(), AXModule.MODULE_ID, AXParticipantRegistrar.PARTICIPANT_ID, delivery.turnId());
        AXOutputProcessor.AXOutputTurn outputTurn = outputProcessor.startTurn(deliveryEnvelope, AXOutputContext.from(delivery), isChatLane(llmPayload));
        llmClient.submit(deliveryEnvelope, llmPayload, new PendingTurn(deliveryEnvelope, delivery, scope, outputTurn));
    }

    private AXScope currentScope() {
        AXScope scope = scopeProvider.currentScope();
        return scope == null ? AXScope.unknown() : scope;
    }

    private void appendTurn(AXScope scope, String role, String content, String iaSessionId, String iaTurnId) {
        if (memorySystem == null || content == null || content.isBlank()) {
            return;
        }
        memorySystem.appendRawTurn(scope, AXRawTurn.dialogue(scope, role, content, iaSessionId, iaTurnId));
    }

    private boolean isChatLane(LLMPromptRequestPayload payload) {
        return payload != null && "CHAT".equalsIgnoreCase(payload.lane());
    }

    private final class PendingTurn implements AXLlmRequestHandler {
        private final TianshuEnvelope deliveryEnvelope;
        private final DialogueDeliveryPayload delivery;
        private final AXScope scope;
        private final AXOutputProcessor.AXOutputTurn outputTurn;
        private final StringBuilder streamed = new StringBuilder();

        private PendingTurn(TianshuEnvelope deliveryEnvelope, DialogueDeliveryPayload delivery, AXScope scope, AXOutputProcessor.AXOutputTurn outputTurn) {
            this.deliveryEnvelope = deliveryEnvelope;
            this.delivery = delivery;
            this.scope = scope;
            this.outputTurn = outputTurn;
        }

        @Override
        public void onStreamChunk(LLMPromptStreamChunkPayload payload) {
            if (payload == null || payload.finished() || payload.text().isEmpty()) {
                return;
            }
            streamed.append(payload.text());
            outputTurn.append(payload.text());
        }

        @Override
        public void onResult(LLMPromptResultPayload payload) {
            if (payload != null && payload.isCompleted()) {
                String text = finalText(payload);
                appendFinalSuffix(text);
                outputTurn.complete(text);
                appendTurn(scope, "assistant", text, delivery.sessionId(), delivery.turnId());
                if (maintenanceCoordinator != null) {
                    maintenanceCoordinator.afterAssistantAnswer(scope);
                }
                sessionController.release(deliveryEnvelope, delivery, DialogueReleaseReason.OWNER_COMPLETED);
                return;
            }
            outputTurn.fail(payload == null ? "LLM returned no result" : payload.errorMessage());
            sessionController.release(deliveryEnvelope, delivery, DialogueReleaseReason.OWNER_FAILED);
        }

        @Override
        public void onCancelled(AXTurnCancellation cancellation) {
            AXTurnCancellation effective = cancellation == null
                    ? AXTurnCancellation.playerInterrupted("AX turn cancelled")
                    : cancellation;
            outputTurn.fail(effective.message());
            sessionController.release(deliveryEnvelope, delivery, effective.releaseReason());
        }

        private String finalText(LLMPromptResultPayload payload) {
            String resultText = payload == null ? "" : payload.text();
            return resultText == null || resultText.isBlank() ? streamed.toString() : resultText;
        }

        private void appendFinalSuffix(String text) {
            if (text == null || text.isBlank() || streamed.isEmpty()) {
                return;
            }
            String current = streamed.toString();
            if (!text.startsWith(current) || text.length() <= current.length()) {
                return;
            }
            String suffix = text.substring(current.length());
            streamed.append(suffix);
            outputTurn.append(suffix);
        }
    }
}
