package com.rheinmetal.tianshu.function.llm.gateway;

import com.rheinmetal.tianshu.function.ia.payload.DialogueLlmUsageAuthorizationRequestPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueLlmUsageAuthorizationResultPayload;
import com.rheinmetal.tianshu.function.llm.LlmProtocolAdapter;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class IaBackedLlmUsageAuthorizer implements LlmUsageAuthorizer {
    private final LlmProtocolAdapter adapter;
    private final Map<String, PendingAuthorization> pendingByEnvelopeId = new ConcurrentHashMap<>();
    private final Map<String, String> envelopeIdByTaskId = new ConcurrentHashMap<>();

    public IaBackedLlmUsageAuthorizer(LlmProtocolAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public LlmUsageAuthorizationStartResult startAuthorization(LlmGatewayRequest request, TianshuEnvelope parent) {
        if (request == null || !request.requiresAuthorization()) {
            return LlmUsageAuthorizationStartResult.startedResult();
        }
        if (!request.hasAuthorizationContext()) {
            return LlmUsageAuthorizationStartResult.rejected("LLM_USAGE_AUTH_DENIED", "Interactive LLM task requires IA authorization context");
        }
        if (parent == null) {
            return LlmUsageAuthorizationStartResult.rejected("LLM_USAGE_AUTH_UNAVAILABLE", "LLM usage authorization parent envelope is missing");
        }
        DialogueLlmUsageAuthorizationRequestPayload payload;
        try {
            payload = new DialogueLlmUsageAuthorizationRequestPayload(
                    request.authorization().sessionId(),
                    request.ragRouting().moduleId(),
                    request.ragRouting().agentId(),
                    request.authorization().turnId(),
                    System.currentTimeMillis()
            );
        } catch (RuntimeException ex) {
            return LlmUsageAuthorizationStartResult.rejected("LLM_USAGE_AUTH_DENIED", ex.getMessage());
        }
        TianshuEnvelope authEnvelope;
        try {
            authEnvelope = adapter.requestLlmUsageAuthorization(parent, payload);
        } catch (RuntimeException ex) {
            return LlmUsageAuthorizationStartResult.rejected("LLM_USAGE_AUTH_UNAVAILABLE", "LLM usage authorization request failed");
        }
        pendingByEnvelopeId.put(authEnvelope.envelopeId(), new PendingAuthorization(request.taskId()));
        envelopeIdByTaskId.put(request.taskId(), authEnvelope.envelopeId());
        return LlmUsageAuthorizationStartResult.startedResult();
    }

    @Override
    public void handleAuthorizationResult(TianshuEnvelope envelope, Consumer<LlmUsageAuthorizationCompletion> completionConsumer) {
        if (envelope == null || !(envelope.payload() instanceof DialogueLlmUsageAuthorizationResultPayload payload)) {
            return;
        }
        PendingAuthorization pending = pendingByEnvelopeId.remove(envelope.parentId());
        if (pending == null) {
            return;
        }
        envelopeIdByTaskId.remove(pending.taskId());
        LlmUsageAuthorizationDecision decision = payload.allowed()
                ? LlmUsageAuthorizationDecision.allow()
                : LlmUsageAuthorizationDecision.denied("LLM_USAGE_AUTH_DENIED", payload.message());
        if (completionConsumer != null) {
            completionConsumer.accept(new LlmUsageAuthorizationCompletion(pending.taskId(), decision));
        }
    }

    @Override
    public void cancel(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        String envelopeId = envelopeIdByTaskId.remove(taskId);
        if (envelopeId != null) {
            pendingByEnvelopeId.remove(envelopeId);
        }
    }

    private record PendingAuthorization(String taskId) {
    }
}
