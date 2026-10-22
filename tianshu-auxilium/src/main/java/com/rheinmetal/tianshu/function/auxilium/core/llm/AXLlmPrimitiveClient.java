package com.rheinmetal.tianshu.function.auxilium.core.llm;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;

public final class AXLlmPrimitiveClient {
    private final AXProtocolAdapter adapter;
    private final long timeoutMillis;
    private final ConcurrentMap<String, PendingQuery> pending = new ConcurrentHashMap<>();

    public AXLlmPrimitiveClient(AXProtocolAdapter adapter) {
        this(adapter, 10_000L);
    }

    public AXLlmPrimitiveClient(AXProtocolAdapter adapter, long timeoutMillis) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.timeoutMillis = Math.max(0L, timeoutMillis);
    }

    public void requestStatus(String requestId, Completion completion) {
        submit(LLMPrimitiveQueryPayload.status(requestId, true), completion);
    }

    public void requestEmbedding(String requestId, List<String> texts, Completion completion) {
        submit(LLMPrimitiveQueryPayload.embed(requestId, texts == null ? List.of() : texts, true, true), completion);
    }

    public void requestTokenCount(String requestId, String text, Completion completion) {
        submit(LLMPrimitiveQueryPayload.tokenCount(requestId, text == null ? "" : text, List.of(), List.of()), completion);
    }

    public void requestMessageTokenCount(String requestId, String role, String content, Completion completion) {
        submit(LLMPrimitiveQueryPayload.tokenCount(
                requestId,
                "",
                List.of(new LLMPrimitiveQueryPayload.MessageItemPayload(role, content)),
                List.of()
        ), completion);
    }

    public OptionalInt countTokens(String requestId, String text) {
        CompletableFuture<LLMPrimitiveResultPayload> future = new CompletableFuture<>();
        requestTokenCount(requestId, text, future::complete);
        return awaitTokenCount(future);
    }

    public OptionalInt countMessageTokens(String requestId, String role, String content) {
        CompletableFuture<LLMPrimitiveResultPayload> future = new CompletableFuture<>();
        requestMessageTokenCount(requestId, role, content, future::complete);
        return awaitTokenCount(future);
    }

    private OptionalInt awaitTokenCount(CompletableFuture<LLMPrimitiveResultPayload> future) {
        try {
            long waitMillis = timeoutMillis <= 0L ? 10_000L : timeoutMillis + 100L;
            LLMPrimitiveResultPayload result = future.get(waitMillis, TimeUnit.MILLISECONDS);
            if (result == null || !LLMPrimitiveResultPayload.STATUS_COMPLETED.equals(result.status())) {
                return OptionalInt.empty();
            }
            if (!LLMPrimitiveQueryPayload.QUERY_TYPE_TOKEN_COUNT.equals(result.queryType())) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(result.tokenCount());
        } catch (Exception ignored) {
            return OptionalInt.empty();
        }
    }

    public void submit(LLMPrimitiveQueryPayload payload, Completion completion) {
        Objects.requireNonNull(completion, "completion");
        if (payload == null || adapter.llmPrimitiveProviderCount() <= 0) {
            completion.complete(LLMPrimitiveResultPayload.failed(
                    payload == null ? "llm.primitive.query" : payload.requestId(),
                    payload == null ? LLMPrimitiveQueryPayload.QUERY_TYPE_STATUS : payload.queryType(),
                    "LLM_PRIMITIVE_PROVIDER_MISSING",
                    "No LLM primitive provider is registered"
            ));
            return;
        }
        TianshuEnvelope envelope = adapter.buildLlmPrimitiveQuery(payload);
        pending.put(envelope.envelopeId(), new PendingQuery(completion, System.currentTimeMillis() + timeoutMillis, payload.requestId(), envelope));
        adapter.registerLlmPrimitiveResultResponse(envelope.envelopeId(), this::handleResponse);
        PendingQuery query = pending.get(envelope.envelopeId());
        scheduleTimeout(envelope.envelopeId(), payload, query);
        adapter.submitLlmPrimitiveQuery(envelope);
    }

    public void sweepExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(entry -> {
            PendingQuery query = entry.getValue();
            if (query == null || query.deadlineMillis() > now) {
                return false;
            }
            query.cancelTimeout("AX primitive query expired");
            adapter.unregisterLlmPrimitiveResponses(entry.getKey());
            adapter.cancelLlmPrimitiveQuery(query.envelope(), "AX_PRIMITIVE_TIMEOUT", "AX LLM primitive query timed out");
            query.completion().complete(LLMPrimitiveResultPayload.failed(
                    "llm.primitive.query",
                    LLMPrimitiveQueryPayload.QUERY_TYPE_STATUS,
                    "LLM_PRIMITIVE_QUERY_TIMEOUT",
                    "AX LLM primitive query timed out"
            ));
            return true;
        });
    }

    public void clear() {
        cancelAll("AX primitive client stopped");
    }

    public void cancelAll(String reason) {
        pending.forEach((requestEnvelopeId, query) -> {
            adapter.unregisterLlmPrimitiveResponses(requestEnvelopeId);
            if (query != null) {
                query.cancelTimeout("AX primitive client stopped");
                adapter.cancelLlmPrimitiveQuery(query.envelope(), "AX_PRIMITIVE_CANCELLED", reason == null ? "AX primitive query cancelled" : reason);
                query.completion().complete(LLMPrimitiveResultPayload.failed(
                        "llm.primitive.query",
                        LLMPrimitiveQueryPayload.QUERY_TYPE_STATUS,
                    "AX_PRIMITIVE_CLIENT_STOPPED",
                    reason == null ? "AX primitive client stopped" : reason
                ));
            }
        });
        pending.clear();
    }

    public boolean cancelRequest(String requestId, String reason) {
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        boolean cancelled = false;
        for (var entry : pending.entrySet()) {
            PendingQuery query = entry.getValue();
            if (query == null || !requestId.equals(query.requestId())) {
                continue;
            }
            if (!pending.remove(entry.getKey(), query)) {
                continue;
            }
            query.cancelTimeout("AX primitive query cancelled");
            adapter.unregisterLlmPrimitiveResponses(entry.getKey());
            String message = reason == null ? "AX primitive query cancelled" : reason;
            adapter.cancelLlmPrimitiveQuery(query.envelope(), "AX_PRIMITIVE_CANCELLED", message);
            query.completion().complete(LLMPrimitiveResultPayload.failed(
                    query.requestId(),
                    LLMPrimitiveQueryPayload.QUERY_TYPE_STATUS,
                    "AX_PRIMITIVE_CANCELLED",
                    message
            ));
            cancelled = true;
        }
        return cancelled;
    }

    private void handleResponse(TianshuEnvelope envelope, ProtocolContext context) {
        PendingQuery query = envelope == null ? null : pending.remove(envelope.parentId());
        if (query == null) {
            if (context != null && envelope != null) {
                context.complete(envelope.envelopeId());
            }
            return;
        }
        LLMPrimitiveResultPayload payload = envelope.payload() instanceof LLMPrimitiveResultPayload result
                ? result
                : LLMPrimitiveResultPayload.failed("llm.primitive.query", LLMPrimitiveQueryPayload.QUERY_TYPE_STATUS, "INVALID_PAYLOAD", "LLM primitive result payload is invalid");
        adapter.unregisterLlmPrimitiveResponses(envelope.parentId());
        query.cancelTimeout("AX primitive response received");
        query.completion().complete(payload);
        if (context != null) {
            context.complete(envelope.envelopeId());
        }
    }

    private void scheduleTimeout(String requestEnvelopeId, LLMPrimitiveQueryPayload payload, PendingQuery query) {
        if (timeoutMillis <= 0L || requestEnvelopeId == null || requestEnvelopeId.isBlank()) {
            return;
        }
        query.timeoutHandle = adapter.scheduleTimeout(
                "ax.primitive.timeout." + requestEnvelopeId,
                () -> timeout(requestEnvelopeId, payload),
                Duration.ofMillis(timeoutMillis)
        );
    }

    private void timeout(String requestEnvelopeId, LLMPrimitiveQueryPayload payload) {
        PendingQuery query = pending.remove(requestEnvelopeId);
        if (query == null) {
            return;
        }
        query.cancelTimeout("AX primitive timeout fired");
        adapter.unregisterLlmPrimitiveResponses(requestEnvelopeId);
        adapter.cancelLlmPrimitiveQuery(query.envelope(), "AX_PRIMITIVE_TIMEOUT", "AX LLM primitive query timed out");
        query.completion().complete(LLMPrimitiveResultPayload.failed(
                payload == null ? "llm.primitive.query" : payload.requestId(),
                payload == null ? LLMPrimitiveQueryPayload.QUERY_TYPE_STATUS : payload.queryType(),
                "LLM_PRIMITIVE_QUERY_TIMEOUT",
                "AX LLM primitive query timed out"
        ));
    }

    public interface Completion {
        void complete(LLMPrimitiveResultPayload result);
    }

    private static final class PendingQuery {
        private final Completion completion;
        private final long deadlineMillis;
        private final String requestId;
        private final TianshuEnvelope envelope;
        private volatile ProtocolTaskHandle timeoutHandle;

        private PendingQuery(Completion completion, long deadlineMillis, String requestId, TianshuEnvelope envelope) {
            this.completion = completion;
            this.deadlineMillis = deadlineMillis;
            this.requestId = requestId;
            this.envelope = envelope;
        }

        private Completion completion() { return completion; }
        private long deadlineMillis() { return deadlineMillis; }
        private String requestId() { return requestId; }
        private TianshuEnvelope envelope() { return envelope; }

        private void cancelTimeout(String reason) {
            ProtocolTaskHandle handle = timeoutHandle;
            if (handle != null && !handle.isDone()) {
                handle.cancel(reason);
            }
        }
    }
}
