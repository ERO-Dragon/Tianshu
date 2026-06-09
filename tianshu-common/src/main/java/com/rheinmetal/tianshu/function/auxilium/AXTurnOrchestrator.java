package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.context.AXContextCollector;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.input.AXDialogueInputMapper;
import com.rheinmetal.tianshu.function.auxilium.input.AXInputNormalizer;
import com.rheinmetal.tianshu.function.auxilium.input.AXNormalizedInput;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.memory.ConversationTurn;
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

import java.util.Objects;

public final class AXTurnOrchestrator {
    private final AXScopeProvider scopeProvider;
    private final AXDialogueInputMapper dialogueInputMapper;
    private final AXInputNormalizer inputNormalizer;
    private final AXRuntimeMaintenanceCoordinator maintenanceCoordinator;
    private final AXContextCollector contextCollector;
    private final AXLlmPromptRequestBuilder llmRequestBuilder;
    private final AXLlmClient llmClient;
    private final AXSessionController sessionController;
    private final AXMemorySystem memorySystem;
    private final AXOutputProcessor outputProcessor;

    public AXTurnOrchestrator(
            AXScopeProvider scopeProvider,
            AXDialogueInputMapper dialogueInputMapper,
            AXInputNormalizer inputNormalizer,
            AXRuntimeMaintenanceCoordinator maintenanceCoordinator,
            AXContextCollector contextCollector,
            AXLlmPromptRequestBuilder llmRequestBuilder,
            AXLlmClient llmClient,
            AXSessionController sessionController,
            AXMemorySystem memorySystem,
            AXOutputProcessor outputProcessor
    ) {
        this.scopeProvider = Objects.requireNonNull(scopeProvider, "scopeProvider");
        this.dialogueInputMapper = Objects.requireNonNull(dialogueInputMapper, "dialogueInputMapper");
        this.inputNormalizer = Objects.requireNonNull(inputNormalizer, "inputNormalizer");
        this.maintenanceCoordinator = maintenanceCoordinator;
        this.contextCollector = Objects.requireNonNull(contextCollector, "contextCollector");
        this.llmRequestBuilder = Objects.requireNonNull(llmRequestBuilder, "llmRequestBuilder");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.sessionController = Objects.requireNonNull(sessionController, "sessionController");
        this.memorySystem = memorySystem;
        this.outputProcessor = Objects.requireNonNull(outputProcessor, "outputProcessor");
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
        AXContextSnapshot context = contextCollector.collect(scope, request);
        appendTurn(scope, "user", request.userText());

        LLMPromptRequestPayload llmPayload = llmRequestBuilder.buildChatRequest(request, context)
                .withDialogueAuthorization(delivery.sessionId(), AXModule.MODULE_ID, AXParticipantRegistrar.PARTICIPANT_ID, delivery.turnId());
        AXOutputProcessor.AXOutputTurn outputTurn = outputProcessor.startTurn(deliveryEnvelope, AXOutputContext.from(delivery), isChatLane(llmPayload));
        llmClient.submit(deliveryEnvelope, llmPayload, new PendingTurn(deliveryEnvelope, delivery, scope, outputTurn));
    }

    private AXScope currentScope() {
        AXScope scope = scopeProvider.currentScope();
        return scope == null ? AXScope.unknown() : scope;
    }

    private void appendTurn(AXScope scope, String role, String content) {
        if (memorySystem == null || content == null || content.isBlank()) {
            return;
        }
        memorySystem.appendConversationTurn(scope, new ConversationTurn(role, content, System.currentTimeMillis()));
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
                appendTurn(scope, "AX", text);
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
