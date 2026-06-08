package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class AXLlmClient {
    private final AXProtocolAdapter adapter;
    private final Map<String, PendingRequest> handlers = new ConcurrentHashMap<>();

    public AXLlmClient(AXProtocolAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    public TianshuEnvelope submit(TianshuEnvelope parent, LLMPromptRequestPayload payload, AXLlmRequestHandler handler) {
        Objects.requireNonNull(parent, "parent");
        return submit(adapter.buildLlmRequest(parent, payload), handler);
    }

    public TianshuEnvelope submitDetached(LLMPromptRequestPayload payload, AXLlmRequestHandler handler) {
        return submit(adapter.buildLlmRequest(payload), handler);
    }

    public boolean handleStreamChunk(String requestEnvelopeId, LLMPromptStreamChunkPayload payload) {
        if (requestEnvelopeId == null || requestEnvelopeId.isBlank()) {
            return false;
        }
        PendingRequest request = handlers.get(requestEnvelopeId);
        if (request == null || request.expired()) {
            handlers.remove(requestEnvelopeId);
            adapter.unregisterLlmResponses(requestEnvelopeId);
            if (request != null) {
                request.handler().onCancelled(AXTurnCancellation.expired("AX LLM request expired"));
            }
            return false;
        }
        request.handler().onStreamChunk(payload);
        return true;
    }

    public boolean handleResult(String requestEnvelopeId, LLMPromptResultPayload payload) {
        if (requestEnvelopeId == null || requestEnvelopeId.isBlank()) {
            return false;
        }
        PendingRequest request = handlers.remove(requestEnvelopeId);
        if (request == null || request.expired()) {
            adapter.unregisterLlmResponses(requestEnvelopeId);
            if (request != null) {
                request.handler().onCancelled(AXTurnCancellation.expired("AX LLM request expired"));
            }
            return false;
        }
        request.handler().onResult(payload);
        adapter.unregisterLlmResponses(requestEnvelopeId);
        return true;
    }

    public void cancelAll(AXTurnCancellation cancellation) {
        AXTurnCancellation effective = cancellation == null
                ? AXTurnCancellation.moduleUnloaded("AX LLM client cancelled")
                : cancellation;
        handlers.forEach((requestEnvelopeId, request) -> {
            request.handler().onCancelled(effective);
            adapter.unregisterLlmResponses(requestEnvelopeId);
        });
        handlers.clear();
    }

    public void clear() {
        cancelAll(AXTurnCancellation.moduleUnloaded("AX module stopped"));
    }

    public void sweepExpired() {
        handlers.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().expired();
            if (expired) {
                adapter.unregisterLlmResponses(entry.getKey());
                entry.getValue().handler().onCancelled(AXTurnCancellation.expired("AX LLM request expired"));
            }
            return expired;
        });
    }

    private TianshuEnvelope submit(TianshuEnvelope envelope, AXLlmRequestHandler handler) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(handler, "handler");
        handlers.put(envelope.envelopeId(), new PendingRequest(handler, envelope.header().expireAt()));
        adapter.registerLlmPromptStreamChunkResponse(envelope.envelopeId(), this::handleStreamChunkResponse);
        adapter.registerLlmPromptResultResponse(envelope.envelopeId(), this::handleResultResponse);
        sweepExpired();
        return adapter.submitLlmRequest(envelope);
    }

    private void handleStreamChunkResponse(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof LLMPromptStreamChunkPayload payload)) {
            if (context != null) {
                context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "AX LLM stream payload is invalid", null);
            }
            return;
        }
        handleStreamChunk(envelope.parentId(), payload);
        if (context != null) {
            context.complete(envelope.envelopeId());
        }
    }

    private void handleResultResponse(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof LLMPromptResultPayload payload)) {
            if (context != null) {
                context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "AX LLM result payload is invalid", null);
            }
            return;
        }
        handleResult(envelope.parentId(), payload);
        if (context != null) {
            context.complete(envelope.envelopeId());
        }
    }

    private record PendingRequest(AXLlmRequestHandler handler, long expireAtMillis) {
        private boolean expired() {
            return expireAtMillis > 0L && expireAtMillis < System.currentTimeMillis();
        }
    }
}
