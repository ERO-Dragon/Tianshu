package com.rheinmetal.tianshu.function.assistant;

import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.llm.LlmSentenceSegmenter;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.payload.StreamTextPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class AssistantDialogueGateway {
    private final AssistantConversationService conversationService;
    private final AssistantProtocolAdapter adapter;
    private final AssistantAccessController accessController;
    private final AssistantLlmRequestFactory requestFactory;
    private final AssistantLlmClient llmClient;

    public AssistantDialogueGateway(AssistantConversationService conversationService, AssistantProtocolAdapter adapter, AssistantAccessController accessController, AssistantLlmRequestFactory requestFactory, AssistantLlmClient llmClient) {
        this.conversationService = Objects.requireNonNull(conversationService, "conversationService");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.accessController = Objects.requireNonNull(accessController, "accessController");
        this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
    }

    public void handleDelivery(TianshuEnvelope envelope, ProtocolContext context) {
        String envelopeId = envelope == null ? "assistant.delivery" : envelope.envelopeId();
        if (!(envelope != null && envelope.payload() instanceof DialogueDeliveryPayload payload)) {
            fail(context, envelopeId, "ASSISTANT_INVALID_PAYLOAD", "Assistant dialogue payload is invalid");
            return;
        }
        if (!accessController.canAcceptDelivery(envelope, payload)) {
            fail(context, envelopeId, "ASSISTANT_ACCESS_DENIED", "Assistant dialogue delivery is not authorized");
            return;
        }
        AssistantInvocationPlan plan = conversationService.prepareInvocation(toAssistantRequest(payload));
        PendingInvocation invocation = new PendingInvocation(envelope, context, plan);
        try {
            llmClient.submit(envelope, requestFactory.create(plan, payload), invocation);
        } catch (RuntimeException ex) {
            fail(context, envelopeId, "ASSISTANT_LLM_SUBMIT_FAILED", "Assistant LLM request submission failed");
        }
    }

    public void handleLlmStreamChunk(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope != null && envelope.payload() instanceof LlmTaskStreamChunkPayload payload) {
            llmClient.handleStreamChunk(envelope.parentId(), payload);
        }
        if (context != null && envelope != null) {
            context.complete(envelope.envelopeId());
        }
    }

    public void handleLlmResult(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope != null && envelope.payload() instanceof LlmTaskResultPayload payload) {
            llmClient.handleResult(envelope.parentId(), payload);
        }
        if (context != null && envelope != null) {
            context.complete(envelope.envelopeId());
        }
    }

    public void cancelActiveGeneration() {
        llmClient.clear();
    }

    private AssistantRequest toAssistantRequest(DialogueDeliveryPayload payload) {
        String text = !payload.repairedText().isBlank() ? payload.repairedText() : payload.normalizedText();
        return new AssistantRequest(payload.requestId(), text, "");
    }

    private void fail(ProtocolContext context, String envelopeId, String reasonCode, String message) {
        if (context != null) {
            context.fail(envelopeId, reasonCode, message, null);
        }
    }

    private final class PendingInvocation implements AssistantLlmRequestHandler {
        private final TianshuEnvelope deliveryEnvelope;
        private final ProtocolContext context;
        private final AssistantInvocationPlan plan;
        private final LlmSentenceSegmenter segmenter = new LlmSentenceSegmenter();
        private final AtomicInteger index = new AtomicInteger();
        private final AtomicBoolean terminalHandled = new AtomicBoolean(false);
        private final StringBuilder collected = new StringBuilder();

        private PendingInvocation(TianshuEnvelope deliveryEnvelope, ProtocolContext context, AssistantInvocationPlan plan) {
            this.deliveryEnvelope = deliveryEnvelope;
            this.context = context;
            this.plan = plan;
        }

        @Override
        public void onStreamChunk(LlmTaskStreamChunkPayload payload) {
            if (payload == null) {
                return;
            }
            if (payload.finished()) {
                publishStreamEnd();
                return;
            }
            if (terminalHandled.get()) {
                return;
            }
            collected.append(payload.text());
            publishSegment(segmenter.accept(payload.text()));
        }

        @Override
        public void onResult(LlmTaskResultPayload payload) {
            if (!terminalHandled.compareAndSet(false, true)) {
                return;
            }
            publishSegment(segmenter.finish());
            publishStreamEnd();
            if (payload != null && "COMPLETED".equals(payload.status())) {
                String text = payload.text().isBlank() ? collected.toString() : payload.text();
                conversationService.completeInvocation(plan, text);
                context.complete(deliveryEnvelope.envelopeId());
                return;
            }
            if (payload != null && "CANCELLED".equals(payload.status())) {
                context.cancel(deliveryEnvelope.envelopeId(), "ASSISTANT_CANCELLED", "Assistant request cancelled");
                return;
            }
            fail(context, deliveryEnvelope.envelopeId(), "ASSISTANT_LLM_FAILED", "Assistant LLM invocation failed");
        }

        private void publishSegment(String text) {
            if (text == null || text.isBlank()) {
                return;
            }
            adapter.publishStreamChunk(deliveryEnvelope, new StreamTextPayload(text, index.getAndIncrement(), false));
        }

        private void publishStreamEnd() {
            adapter.publishStreamEnd(deliveryEnvelope, index.get());
        }
    }
}
