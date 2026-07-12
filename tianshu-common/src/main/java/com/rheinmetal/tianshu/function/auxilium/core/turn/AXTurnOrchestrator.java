package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextCollector;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXRuntimeLlmBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXRuntimeLlmBudgetResolver;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXDynamicFact;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXDynamicFactClient;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSystem;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXDialogueInputMapper;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXInputNormalizer;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXNormalizedInput;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputContext;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputProcessor;
import com.rheinmetal.tianshu.function.auxilium.core.maintenance.AXRuntimeMaintenanceCoordinator;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeProvider;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.AXMemoryRetrievalRequest;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.AXMemoryRetrievalResult;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.AXMemoryRetriever;
import com.rheinmetal.tianshu.function.ia.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;

import java.util.List;
import java.util.Objects;
import com.rheinmetal.tianshu.function.auxilium.AXModule;
import com.rheinmetal.tianshu.function.auxilium.AXParticipantRegistrar;
import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmClient;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPromptRequestBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmRequestHandler;

public final class AXTurnOrchestrator implements AXTurnPipeline {
    private final AXScopeProvider scopeProvider;
    private final AXDialogueInputMapper dialogueInputMapper;
    private final AXInputNormalizer inputNormalizer;
    private final AXRuntimeMaintenanceCoordinator maintenanceCoordinator;
    private final AXDynamicFactClient dynamicFactClient;
    private final AXContextCollector contextCollector;
    private final AXLlmPromptRequestBuilder llmRequestBuilder;
    private final AXRuntimeLlmBudgetResolver budgetResolver;
    private final AXRuntimeLlmBudget fallbackRuntimeBudget;
    private final AXLlmClient llmClient;
    private final AXSessionController sessionController;
    private final AXMemorySystem memorySystem;
    private final AXRecentDialogueSystem recentDialogueSystem;
    private final AXOutputProcessor outputProcessor;
    private final AXMemoryRetriever memoryRetriever;
    private final AXTurnStatusPublisher statusPublisher;

    public AXTurnOrchestrator(
            AXScopeProvider scopeProvider,
            AXDialogueInputMapper dialogueInputMapper,
            AXInputNormalizer inputNormalizer,
            AXRuntimeMaintenanceCoordinator maintenanceCoordinator,
            AXDynamicFactClient dynamicFactClient,
            AXContextCollector contextCollector,
            AXLlmPromptRequestBuilder llmRequestBuilder,
            AXContextBudget contextBudget,
            AXRuntimeLlmBudgetResolver budgetResolver,
            AXLlmClient llmClient,
            AXSessionController sessionController,
            AXMemorySystem memorySystem,
            AXRecentDialogueSystem recentDialogueSystem,
            AXOutputProcessor outputProcessor,
            AXMemoryRetriever memoryRetriever
    ) {
        this(
                scopeProvider,
                dialogueInputMapper,
                inputNormalizer,
                maintenanceCoordinator,
                dynamicFactClient,
                contextCollector,
                llmRequestBuilder,
                contextBudget,
                budgetResolver,
                llmClient,
                sessionController,
                memorySystem,
                recentDialogueSystem,
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
            AXDynamicFactClient dynamicFactClient,
            AXContextCollector contextCollector,
            AXLlmPromptRequestBuilder llmRequestBuilder,
            AXContextBudget contextBudget,
            AXRuntimeLlmBudgetResolver budgetResolver,
            AXLlmClient llmClient,
            AXSessionController sessionController,
            AXMemorySystem memorySystem,
            AXRecentDialogueSystem recentDialogueSystem,
            AXOutputProcessor outputProcessor,
            AXMemoryRetriever memoryRetriever,
            AXTurnStatusPublisher statusPublisher
    ) {
        this.scopeProvider = Objects.requireNonNull(scopeProvider, "scopeProvider");
        this.dialogueInputMapper = Objects.requireNonNull(dialogueInputMapper, "dialogueInputMapper");
        this.inputNormalizer = Objects.requireNonNull(inputNormalizer, "inputNormalizer");
        this.maintenanceCoordinator = maintenanceCoordinator;
        this.dynamicFactClient = dynamicFactClient;
        this.contextCollector = Objects.requireNonNull(contextCollector, "contextCollector");
        this.llmRequestBuilder = Objects.requireNonNull(llmRequestBuilder, "llmRequestBuilder");
        AXContextBudget fallbackContextBudget = contextBudget == null ? AXContextBudget.DEFAULT : contextBudget;
        this.budgetResolver = budgetResolver;
        this.fallbackRuntimeBudget = new AXRuntimeLlmBudget(null, fallbackContextBudget);
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.sessionController = Objects.requireNonNull(sessionController, "sessionController");
        this.memorySystem = memorySystem;
        this.recentDialogueSystem = recentDialogueSystem;
        this.outputProcessor = Objects.requireNonNull(outputProcessor, "outputProcessor");
        this.memoryRetriever = memoryRetriever;
        this.statusPublisher = statusPublisher;
    }

    public AXTurnOrchestrator(
            AXScopeProvider scopeProvider,
            AXDialogueInputMapper dialogueInputMapper,
            AXInputNormalizer inputNormalizer,
            AXRuntimeMaintenanceCoordinator maintenanceCoordinator,
            AXDynamicFactClient dynamicFactClient,
            AXContextCollector contextCollector,
            AXLlmPromptRequestBuilder llmRequestBuilder,
            AXRuntimeLlmBudgetResolver budgetResolver,
            AXLlmClient llmClient,
            AXSessionController sessionController,
            AXMemorySystem memorySystem,
            AXRecentDialogueSystem recentDialogueSystem,
            AXOutputProcessor outputProcessor
    ) {
        this(
                scopeProvider,
                dialogueInputMapper,
                inputNormalizer,
                maintenanceCoordinator,
                dynamicFactClient,
                contextCollector,
                llmRequestBuilder,
                AXContextBudget.DEFAULT,
                budgetResolver,
                llmClient,
                sessionController,
                memorySystem,
                recentDialogueSystem,
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
        if (dynamicFactClient == null) {
            continueTurn(deliveryEnvelope, delivery, scope, request, List.of());
            return;
        }
        dynamicFactClient.sweepExpired();
        dynamicFactClient.request(deliveryEnvelope, delivery, scope, request, candidates ->
                continueTurn(deliveryEnvelope, delivery, scope, request, candidates));
    }

    private void continueTurn(TianshuEnvelope deliveryEnvelope, DialogueDeliveryPayload delivery, AXScope scope, AXRequest request, List<AXDynamicFact> dynamicFacts) {
        if (budgetResolver == null) {
            continueTurnWithBudget(deliveryEnvelope, delivery, scope, request, dynamicFacts, fallbackRuntimeBudget);
            return;
        }
        budgetResolver.resolveContextBudget(request.requestKey(), budget ->
                continueTurnWithBudget(deliveryEnvelope, delivery, scope, request, dynamicFacts, budget));
    }

    private void continueTurnWithBudget(TianshuEnvelope deliveryEnvelope, DialogueDeliveryPayload delivery, AXScope scope, AXRequest request, List<AXDynamicFact> dynamicFacts, AXRuntimeLlmBudget runtimeBudget) {
        AXRuntimeLlmBudget budget = runtimeBudget == null ? fallbackRuntimeBudget : runtimeBudget;
        if (memoryRetriever == null) {
            submitLlmTurn(deliveryEnvelope, delivery, scope, request, dynamicFacts, AXMemoryRetrievalResult.empty(), budget.contextBudget());
            return;
        }
        if (statusPublisher != null) {
            statusPublisher.retrievingMemory();
        }
        memoryRetriever.retrieve(
                new AXMemoryRetrievalRequest(
                        scope,
                        request,
                        budget.contextBudget().maxRetrievedMemoryItems(),
                        budget.contextBudget().retrievedMemoryTokenBudget()
                ),
                memory -> submitLlmTurn(deliveryEnvelope, delivery, scope, request, dynamicFacts, memory, budget.contextBudget())
        );
    }

    private void submitLlmTurn(TianshuEnvelope deliveryEnvelope, DialogueDeliveryPayload delivery, AXScope scope, AXRequest request, List<AXDynamicFact> dynamicFacts, AXMemoryRetrievalResult memoryRetrieval, AXContextBudget contextBudget) {
        AXContextSnapshot context = contextCollector.collect(scope, request, dynamicFacts);
        if (memoryRetrieval != null && !memoryRetrieval.blocks().isEmpty()) {
            context = new AXContextSnapshot(
                    context.scope(),
                    context.memory().withRetrievedPlayerMemoryBlocks(memoryRetrieval.blocks()),
                    context.recentDialogue(),
                    context.dynamicFacts(),
                    context.deliverySnapshot()
            );
        }
        LLMPromptRequestPayload llmPayload = llmRequestBuilder.buildChatRequest(request, context, contextBudget)
                .withDialogueAuthorization(delivery.sessionId(), AXModule.MODULE_ID, AXParticipantRegistrar.PARTICIPANT_ID, delivery.turnId());
        appendTurn(scope, "user", request.userText(), delivery.sessionId(), delivery.turnId());
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
        appendTurn(scope, role, content, iaSessionId, iaTurnId, 0);
    }

    private void appendTurn(AXScope scope, String role, String content, String iaSessionId, String iaTurnId, int tokenCount) {
        if (recentDialogueSystem == null || content == null || content.isBlank()) {
            return;
        }
        AXRawTurn turn = AXRawTurn.dialogue(scope, role, content, iaSessionId, iaTurnId);
        recentDialogueSystem.append(scope, tokenCount > 0 ? turn.withTokenCount(tokenCount) : turn);
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
                appendAssistantVisibleText(text, payload);
                if (maintenanceCoordinator != null) {
                    maintenanceCoordinator.afterAssistantAnswer(scope);
                }
                sessionController.release(deliveryEnvelope, delivery, DialogueReleaseReason.OWNER_COMPLETED);
                return;
            }
            appendAssistantVisibleText(finalText(payload), payload);
            if (statusPublisher != null) {
                statusPublisher.failed(resultFailureReason(payload));
            }
            failOutput(payload == null ? "LLM returned no result" : payload.errorMessage(), "output.failure_notice_failed");
            sessionController.release(deliveryEnvelope, delivery, DialogueReleaseReason.OWNER_FAILED);
        }

        @Override
        public void onCancellationResult(LLMPromptResultPayload payload) {
            appendAssistantVisibleText(finalText(payload), payload);
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

        private void appendAssistantVisibleText(String text, LLMPromptResultPayload payload) {
            if (text == null || text.isBlank()) {
                return;
            }
            appendTurn(scope, "assistant", text, delivery.sessionId(), delivery.turnId(), visibleCompletionTokens(payload));
        }

        private int visibleCompletionTokens(LLMPromptResultPayload payload) {
            if (payload == null || payload.usage() == null) {
                return 0;
            }
            return Math.max(0, payload.usage().completionTokens());
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
