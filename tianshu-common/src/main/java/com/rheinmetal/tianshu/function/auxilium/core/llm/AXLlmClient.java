package com.rheinmetal.tianshu.function.auxilium.core.llm;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;

public final class AXLlmClient {
    private final AXProtocolAdapter adapter;
    private final LongSupplier nowMillis;
    private final Map<String, PendingRequest> handlers = new ConcurrentHashMap<>();

    public AXLlmClient(AXProtocolAdapter adapter) {
        this(adapter, System::currentTimeMillis);
    }

    AXLlmClient(AXProtocolAdapter adapter, LongSupplier nowMillis) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.nowMillis = nowMillis == null ? System::currentTimeMillis : nowMillis;
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
        if (request == null || request.expired(nowMillis.getAsLong())) {
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
        if (request == null || request.expired(nowMillis.getAsLong())) {
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

    public boolean cancelChatRequests(AXTurnCancellation cancellation) {
        return cancelRequestsByLane("CHAT", cancellation == null
                ? AXTurnCancellation.playerInterrupted("AX chat request cancelled")
                : cancellation);
    }

    public boolean cancelRequest(String requestEnvelopeId, AXTurnCancellation cancellation) {
        if (requestEnvelopeId == null || requestEnvelopeId.isBlank()) {
            return false;
        }
        PendingRequest request = handlers.remove(requestEnvelopeId);
        if (request == null) {
            return false;
        }
        AXTurnCancellation effective = cancellation == null
                ? AXTurnCancellation.moduleUnloaded("AX LLM request cancelled")
                : cancellation;
        request.handler().onCancelled(effective);
        adapter.unregisterLlmResponses(requestEnvelopeId);
        return true;
    }

    public void clear() {
        cancelAll(AXTurnCancellation.moduleUnloaded("AX module stopped"));
    }

    public void sweepExpired() {
        handlers.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().expired(nowMillis.getAsLong());
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
        handlers.put(envelope.envelopeId(), new PendingRequest(handler, envelope.header().expireAt(), payloadLane(envelope)));
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

    private boolean cancelRequestsByLane(String lane, AXTurnCancellation cancellation) {
        String expectedLane = lane == null ? "" : lane.trim().toUpperCase();
        if (expectedLane.isBlank()) {
            return false;
        }
        AXTurnCancellation effective = cancellation == null
                ? AXTurnCancellation.moduleUnloaded("AX LLM request cancelled")
                : cancellation;
        AtomicBoolean cancelled = new AtomicBoolean(false);
        handlers.entrySet().removeIf(entry -> {
            PendingRequest request = entry.getValue();
            if (request == null || !expectedLane.equals(request.lane())) {
                return false;
            }
            request.handler().onCancelled(effective);
            adapter.unregisterLlmResponses(entry.getKey());
            cancelled.set(true);
            return true;
        });
        return cancelled.get();
    }

    private String payloadLane(TianshuEnvelope envelope) {
        if (envelope != null && envelope.payload() instanceof LLMPromptRequestPayload payload) {
            return payload.lane();
        }
        return "CHAT";
    }

    private record PendingRequest(AXLlmRequestHandler handler, long expireAtMillis, String lane) {
        private PendingRequest {
            lane = lane == null || lane.isBlank() ? "CHAT" : lane.trim().toUpperCase();
        }

        private boolean expired(long nowMillis) {
            if ("TASK".equals(lane)) {
                return false;
            }
            return expireAtMillis > 0L && expireAtMillis < nowMillis;
        }
    }
}
