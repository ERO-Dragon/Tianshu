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
    private final AXTurnStatusPublisher statusPublisher;

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
        this(
                scopeProvider,
                dialogueInputMapper,
                inputNormalizer,
                maintenanceCoordinator,
                runtimeContextClient,
                contextCollector,
                llmRequestBuilder,
                contextBudget,
                llmClient,
                sessionController,
                memorySystem,
                outputProcessor,
                memoryRetriever,
                null
        );
    }

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
            AXMemoryRetriever memoryRetriever,
            AXTurnStatusPublisher statusPublisher
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
        this.statusPublisher = statusPublisher;
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
        if (statusPublisher != null) {
            statusPublisher.accepted();
            statusPublisher.processing();
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
        if (statusPublisher != null) {
            statusPublisher.retrievingMemory();
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
        if (statusPublisher != null) {
            statusPublisher.thinking();
        }
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
        private boolean respondingPublished;

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
            publishRespondingOnce();
            streamed.append(payload.text());
            appendOutput(payload.text(), "output.stream_failed");
        }

        @Override
        public void onResult(LLMPromptResultPayload payload) {
            if (payload != null && payload.isCompleted()) {
                String text = finalText(payload);
                if (!text.isBlank()) {
                    publishRespondingOnce();
                }
                appendFinalSuffix(text);
                if (!completeOutput(text)) {
                    sessionController.release(deliveryEnvelope, delivery, DialogueReleaseReason.OWNER_FAILED);
                    return;
                }
                appendTurn(scope, "assistant", text, delivery.sessionId(), delivery.turnId());
                if (maintenanceCoordinator != null) {
                    maintenanceCoordinator.afterAssistantAnswer(scope);
                }
                sessionController.release(deliveryEnvelope, delivery, DialogueReleaseReason.OWNER_COMPLETED);
                return;
            }
            if (statusPublisher != null) {
                statusPublisher.failed(resultFailureReason(payload));
            }
            failOutput(payload == null ? "LLM returned no result" : payload.errorMessage(), "output.failure_notice_failed");
            sessionController.release(deliveryEnvelope, delivery, DialogueReleaseReason.OWNER_FAILED);
        }

        @Override
        public void onCancelled(AXTurnCancellation cancellation) {
            AXTurnCancellation effective = cancellation == null
                    ? AXTurnCancellation.playerInterrupted("AX turn cancelled")
                    : cancellation;
            if (statusPublisher != null) {
                if (effective.releaseReason() == DialogueReleaseReason.PLAYER_CANCELLED) {
                    statusPublisher.interrupted();
                } else {
                    statusPublisher.failed("cancelled." + effective.releaseReason().name());
                }
            }
            failOutput(effective.message(), "output.cancel_notice_failed");
            sessionController.release(deliveryEnvelope, delivery, effective.releaseReason());
        }

        private void publishRespondingOnce() {
            if (respondingPublished || statusPublisher == null) {
                return;
            }
            respondingPublished = true;
            statusPublisher.responding();
        }

        private String resultFailureReason(LLMPromptResultPayload payload) {
            if (payload == null) {
                return "llm.result.missing";
            }
            if (payload.errorCode() != null && !payload.errorCode().isBlank()) {
                return "llm." + payload.errorCode();
            }
            return "llm." + payload.status().toLowerCase(java.util.Locale.ROOT);
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
            appendOutput(suffix, "output.final_suffix_failed");
        }

        private boolean appendOutput(String text, String reasonCode) {
            try {
                outputTurn.append(text);
                return true;
            } catch (RuntimeException exception) {
                publishOutputFailed(reasonCode);
                failOutput("", "output.failure_notice_failed");
                return false;
            }
        }

        private boolean completeOutput(String text) {
            try {
                outputTurn.complete(text);
                return true;
            } catch (RuntimeException exception) {
                publishOutputFailed("output.complete_failed");
                failOutput("", "output.failure_notice_failed");
                return false;
            }
        }

        private void failOutput(String reason, String fallbackReasonCode) {
            try {
                outputTurn.fail(reason == null ? "" : reason);
            } catch (RuntimeException exception) {
                publishOutputFailed(fallbackReasonCode);
            }
        }

        private void publishOutputFailed(String reasonCode) {
            if (statusPublisher != null) {
                statusPublisher.failed(reasonCode);
            }
        }
    }
}
