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
            if (request != null) {
                cancelAndForget(requestEnvelopeId, request, AXTurnCancellation.expired("AX LLM request expired"));
            }
            return false;
        }
        if (request.cancelling()) {
            return true;
        }
        request.handler().onStreamChunk(payload);
        return true;
    }

    public boolean handleResult(String requestEnvelopeId, LLMPromptResultPayload payload) {
        if (requestEnvelopeId == null || requestEnvelopeId.isBlank()) {
            return false;
        }
        PendingRequest request = handlers.remove(requestEnvelopeId);
        if (request == null) {
            adapter.unregisterLlmResponses(requestEnvelopeId);
            return false;
        }
        adapter.unregisterLlmResponses(requestEnvelopeId);
        if (request.cancelling()) {
            request.handler().onCancellationResult(payload);
            return true;
        }
        if (request.expired(nowMillis.getAsLong())) {
            request.notifyCancelled(AXTurnCancellation.expired("AX LLM request expired"));
            return false;
        }
        request.handler().onResult(payload);
        return true;
    }

    public void cancelAll(AXTurnCancellation cancellation) {
        AXTurnCancellation effective = cancellation == null
                ? AXTurnCancellation.moduleUnloaded("AX LLM client cancelled")
                : cancellation;
        handlers.forEach((requestEnvelopeId, request) -> {
            cancelAndForget(requestEnvelopeId, request, effective);
        });
        handlers.clear();
    }

    public boolean cancelChatRequests(AXTurnCancellation cancellation) {
        return cancelChatRequestsByLane(cancellation == null
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
        cancelAndForget(requestEnvelopeId, request, effective);
        return true;
    }

    public void clear() {
        cancelAll(AXTurnCancellation.moduleUnloaded("AX module stopped"));
    }

    public void sweepExpired() {
        handlers.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().expired(nowMillis.getAsLong());
            if (expired) {
                cancelAndForget(entry.getKey(), entry.getValue(), AXTurnCancellation.expired("AX LLM request expired"));
            }
            return expired;
        });
    }

    private TianshuEnvelope submit(TianshuEnvelope envelope, AXLlmRequestHandler handler) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(handler, "handler");
        handlers.put(envelope.envelopeId(), new PendingRequest(envelope, handler, envelope.header().expireAt(), payloadLane(envelope)));
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

    private boolean cancelChatRequestsByLane(AXTurnCancellation cancellation) {
        AXTurnCancellation effective = cancellation == null
                ? AXTurnCancellation.moduleUnloaded("AX LLM request cancelled")
                : cancellation;
        AtomicBoolean cancelled = new AtomicBoolean(false);
        handlers.entrySet().removeIf(entry -> {
            PendingRequest request = entry.getValue();
            if (request == null || !"CHAT".equals(request.lane())) {
                return false;
            }
            cancelAndAwaitTerminalResult(request, effective);
            cancelled.set(true);
            return false;
        });
        return cancelled.get();
    }

    private void cancelAndAwaitTerminalResult(PendingRequest request, AXTurnCancellation cancellation) {
        AXTurnCancellation effective = cancellation == null
                ? AXTurnCancellation.moduleUnloaded("AX LLM request cancelled")
                : cancellation;
        if (!request.markCancelling()) {
            return;
        }
        try {
            request.notifyCancelled(effective);
        } finally {
            adapter.cancelLlmRequest(request.envelope(), cancellationReasonCode(effective), effective.message());
        }
    }

    private void cancelAndForget(String requestEnvelopeId, PendingRequest request, AXTurnCancellation cancellation) {
        AXTurnCancellation effective = cancellation == null
                ? AXTurnCancellation.moduleUnloaded("AX LLM request cancelled")
                : cancellation;
        try {
            adapter.unregisterLlmResponses(requestEnvelopeId);
            adapter.cancelLlmRequest(request.envelope(), cancellationReasonCode(effective), effective.message());
        } finally {
            request.notifyCancelled(effective);
        }
    }

    private static String cancellationReasonCode(AXTurnCancellation cancellation) {
        if (cancellation == null || cancellation.releaseReason() == null) {
            return "AX_LLM_REQUEST_CANCELLED";
        }
        return "AX_" + cancellation.releaseReason().name();
    }

    private String payloadLane(TianshuEnvelope envelope) {
        if (envelope != null && envelope.payload() instanceof LLMPromptRequestPayload payload) {
            return payload.lane();
        }
        return "CHAT";
    }

    private static final class PendingRequest {
        private final TianshuEnvelope envelope;
        private final AXLlmRequestHandler handler;
        private final long expireAtMillis;
        private final String lane;
        private final AtomicBoolean cancelling = new AtomicBoolean(false);
        private final AtomicBoolean cancellationNotified = new AtomicBoolean(false);

        private PendingRequest(TianshuEnvelope envelope, AXLlmRequestHandler handler, long expireAtMillis, String lane) {
            Objects.requireNonNull(envelope, "envelope");
            Objects.requireNonNull(handler, "handler");
            this.envelope = envelope;
            this.handler = handler;
            this.expireAtMillis = expireAtMillis;
            this.lane = lane == null || lane.isBlank() ? "CHAT" : lane.trim().toUpperCase();
        }

        private TianshuEnvelope envelope() {
            return envelope;
        }

        private AXLlmRequestHandler handler() {
            return handler;
        }

        private String lane() {
            return lane;
        }

        private boolean cancelling() {
            return cancelling.get();
        }

        private boolean markCancelling() {
            return cancelling.compareAndSet(false, true);
        }

        private void notifyCancelled(AXTurnCancellation cancellation) {
            if (cancellationNotified.compareAndSet(false, true)) {
                handler.onCancelled(cancellation);
            }
        }

        private boolean expired(long nowMillis) {
            if ("TASK".equals(lane)) {
                return false;
            }
            return expireAtMillis > 0L && expireAtMillis < nowMillis;
        }
    }
}
