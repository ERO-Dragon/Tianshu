package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskStreamChunkPayload;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class AXLlmClient {
    private final AXProtocolAdapter adapter;
    private final Map<String, PendingRequest> handlers = new ConcurrentHashMap<>();

    public AXLlmClient(AXProtocolAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    public TianshuEnvelope submit(TianshuEnvelope parent, LlmTaskRequestPayload payload, AXLlmRequestHandler handler) {
        Objects.requireNonNull(parent, "parent");
        return submit(adapter.requestLlm(parent, payload), handler);
    }

    public TianshuEnvelope submitDetached(LlmTaskRequestPayload payload, AXLlmRequestHandler handler) {
        return submit(adapter.requestLlm(payload), handler);
    }

    public boolean handleStreamChunk(String requestEnvelopeId, LlmTaskStreamChunkPayload payload) {
        PendingRequest request = handlers.get(requestEnvelopeId);
        if (request == null || request.expired()) {
            handlers.remove(requestEnvelopeId);
            return false;
        }
        request.handler().onStreamChunk(payload);
        return true;
    }

    public boolean handleResult(String requestEnvelopeId, LlmTaskResultPayload payload) {
        PendingRequest request = handlers.remove(requestEnvelopeId);
        if (request == null || request.expired()) {
            return false;
        }
        request.handler().onResult(payload);
        return true;
    }

    public void clear() {
        handlers.clear();
    }

    public void sweepExpired() {
        handlers.entrySet().removeIf(entry -> entry.getValue().expired());
    }

    private TianshuEnvelope submit(TianshuEnvelope envelope, AXLlmRequestHandler handler) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(handler, "handler");
        handlers.put(envelope.envelopeId(), new PendingRequest(handler, envelope.header().expireAt()));
        sweepExpired();
        return envelope;
    }

    private record PendingRequest(AXLlmRequestHandler handler, long expireAtMillis) {
        private boolean expired() {
            return expireAtMillis > 0L && expireAtMillis < System.currentTimeMillis();
        }
    }
}
