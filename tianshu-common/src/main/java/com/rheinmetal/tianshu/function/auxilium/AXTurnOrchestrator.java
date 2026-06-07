package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.context.AXContextCollector;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.input.AXInputNormalizer;
import com.rheinmetal.tianshu.function.auxilium.input.AXInputSource;
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
        AXRequest rawRequest = requestFromDelivery(delivery);
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

        LLMPromptRequestPayload llmPayload = llmRequestBuilder.buildChatRequest(request, context);
        AXOutputProcessor.AXOutputTurn outputTurn = outputProcessor.startTurn(deliveryEnvelope, AXOutputContext.from(delivery), isChatLane(llmPayload));
        llmClient.submit(deliveryEnvelope, llmPayload, new PendingTurn(deliveryEnvelope, delivery, scope, outputTurn));
    }

    private AXScope currentScope() {
        AXScope scope = scopeProvider.currentScope();
        return scope == null ? AXScope.unknown() : scope;
    }

    private AXRequest requestFromDelivery(DialogueDeliveryPayload delivery) {
        String userText = !delivery.normalizedText().isBlank() ? delivery.normalizedText() : delivery.repairedText();
        return new AXRequest(requestKey(delivery), userText, providedContext(delivery), AXInputSource.FORWARDED);
    }

    private String requestKey(DialogueDeliveryPayload delivery) {
        String turnId = delivery.turnId() == null || delivery.turnId().isBlank() ? "turn" : delivery.turnId();
        return "AX." + delivery.sessionId() + "." + turnId;
    }

    private String providedContext(DialogueDeliveryPayload delivery) {
        StringBuilder builder = new StringBuilder();
        if (!delivery.playerId().isBlank()) {
            appendLine(builder, "playerId=" + delivery.playerId());
        }
        if (delivery.contextSnapshot() != null && !delivery.contextSnapshot().dimensionId().isBlank()) {
            appendLine(builder, "dimension=" + delivery.contextSnapshot().dimensionId());
        }
        if (!delivery.matchedHotwords().isEmpty()) {
            appendLine(builder, "matchedHotwords=" + String.join(", ", delivery.matchedHotwords()));
        }
        if (!delivery.matchedItemIds().isEmpty()) {
            appendLine(builder, "matchedItems=" + String.join(", ", delivery.matchedItemIds()));
        }
        if (!delivery.matchedEntityRefs().isEmpty()) {
            appendLine(builder, "matchedEntities=" + String.join(", ", delivery.matchedEntityRefs()));
        }
        if (delivery.interactionHints() != null) {
            if (!delivery.interactionHints().heldItemId().isBlank()) {
                appendLine(builder, "heldItem=" + delivery.interactionHints().heldItemId());
            }
            if (delivery.interactionHints().crosshairHit()) {
                appendLine(builder, "crosshairHit=true");
            }
            if (delivery.interactionHints().interactionKeyDown()) {
                appendLine(builder, "interactionKeyDown=true");
            }
            if (delivery.interactionHints().targetDistance() > 0.0D) {
                appendLine(builder, "targetDistance=" + delivery.interactionHints().targetDistance());
            }
        }
        if (delivery.contextSnapshot() != null && !delivery.contextSnapshot().facts().isEmpty()) {
            delivery.contextSnapshot().facts().forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                    appendLine(builder, key.trim() + "=" + value.trim());
                }
            });
        }
        return builder.toString().trim();
    }

    private void appendLine(StringBuilder builder, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(line);
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
                String text = payload.text().isBlank() ? streamed.toString() : payload.text();
                outputTurn.complete(text);
                appendTurn(scope, "AX", text);
                sessionController.release(deliveryEnvelope, delivery, DialogueReleaseReason.OWNER_COMPLETED);
                return;
            }
            outputTurn.fail(payload == null ? "LLM returned no result" : payload.errorMessage());
            sessionController.release(deliveryEnvelope, delivery, DialogueReleaseReason.OWNER_FAILED);
        }
    }
}
