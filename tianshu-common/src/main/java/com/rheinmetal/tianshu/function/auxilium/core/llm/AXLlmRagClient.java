package com.rheinmetal.tianshu.function.auxilium.core.llm;

import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManagePayload;
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManageResultPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public final class AXLlmRagClient {
    private final AXProtocolAdapter adapter;
    private final long timeoutMillis;
    private final ConcurrentMap<String, PendingQuery> pending = new ConcurrentHashMap<>();

    public AXLlmRagClient(AXProtocolAdapter adapter) {
        this(adapter, 2_000L);
    }

    public AXLlmRagClient(AXProtocolAdapter adapter, long timeoutMillis) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.timeoutMillis = Math.max(0L, timeoutMillis);
    }

    public CompletableFuture<LLMCacheManageResultPayload> registerLibrary(String uid, String modid, String visibility, java.util.List<String> tags) {
        return submit(LLMCacheManagePayload.registerLibrary(uid, modid, visibility, tags));
    }

    public CompletableFuture<LLMCacheManageResultPayload> clearUid(String uid) {
        return submit(LLMCacheManagePayload.clearUid(uid));
    }

    public CompletableFuture<LLMCacheManageResultPayload> upsertEntry(String uid, String entryId, String content, float[] vector) {
        return submit(LLMCacheManagePayload.upsertEntry(uid, entryId, content, vector));
    }

    public CompletableFuture<LLMCacheManageResultPayload> searchUid(String uid, String queryText, int topK, float threshold) {
        return submit(LLMCacheManagePayload.searchUid(uid, queryText, topK, threshold));
    }

    public CompletableFuture<LLMCacheManageResultPayload> searchTags(java.util.List<String> tags, String queryText, int topK, float threshold) {
        return submit(LLMCacheManagePayload.searchTags(tags, queryText, topK, threshold));
    }

    public CompletableFuture<LLMCacheManageResultPayload> searchInlineContents(String uid, String queryText, java.util.List<String> contents, int topK, float threshold) {
        return submit(LLMCacheManagePayload.searchInlineContents(uid, queryText, contents, topK, threshold));
    }

    public CompletableFuture<LLMCacheManageResultPayload> submit(LLMCacheManagePayload payload) {
        CompletableFuture<LLMCacheManageResultPayload> future = new CompletableFuture<>();
        if (payload == null || adapter.llmCacheManageProviderCount() <= 0) {
            future.complete(LLMCacheManageResultPayload.failed(
                    payload == null ? "" : payload.uid(),
                    "No LLM cache manage provider is registered"
            ));
            return future;
        }
        TianshuEnvelope envelope = adapter.buildLlmCacheManage(payload);
        pending.put(envelope.envelopeId(), new PendingQuery(future, System.currentTimeMillis() + timeoutMillis, payload.uid()));
        adapter.registerLlmCacheManageResultResponse(envelope.envelopeId(), this::handleResponse);
        adapter.submitLlmCacheManage(envelope);
        scheduleTimeout(envelope.envelopeId(), payload.uid());
        return future;
    }

    public void sweepExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(entry -> {
            PendingQuery query = entry.getValue();
            if (query == null || query.deadlineMillis() > now) {
                return false;
            }
            adapter.unregisterLlmCacheManageResponses(entry.getKey());
            query.future().complete(LLMCacheManageResultPayload.failed(query.uid(), "AX LLM RAG query timed out"));
            return true;
        });
    }

    public void clear() {
        pending.forEach((requestEnvelopeId, query) -> {
            adapter.unregisterLlmCacheManageResponses(requestEnvelopeId);
            if (query != null) {
                query.future().complete(LLMCacheManageResultPayload.failed(query.uid(), "AX RAG client stopped"));
            }
        });
        pending.clear();
    }

    private void handleResponse(TianshuEnvelope envelope, ProtocolContext context) {
        PendingQuery query = envelope == null ? null : pending.remove(envelope.parentId());
        if (query == null) {
            if (context != null && envelope != null) {
                context.complete(envelope.envelopeId());
            }
            return;
        }
        LLMCacheManageResultPayload payload = envelope.payload() instanceof LLMCacheManageResultPayload result
                ? result
                : LLMCacheManageResultPayload.failed(query.uid(), "LLM cache manage result payload is invalid");
        adapter.unregisterLlmCacheManageResponses(envelope.parentId());
        query.future().complete(payload);
        if (context != null) {
            context.complete(envelope.envelopeId());
        }
    }

    private void scheduleTimeout(String requestEnvelopeId, String uid) {
        if (timeoutMillis <= 0L || requestEnvelopeId == null || requestEnvelopeId.isBlank()) {
            return;
        }
        CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS).execute(() -> timeout(requestEnvelopeId, uid));
    }

    private void timeout(String requestEnvelopeId, String uid) {
        PendingQuery query = pending.remove(requestEnvelopeId);
        if (query == null) {
            return;
        }
        adapter.unregisterLlmCacheManageResponses(requestEnvelopeId);
        query.future().complete(LLMCacheManageResultPayload.failed(uid, "AX LLM RAG query timed out"));
    }

    private record PendingQuery(CompletableFuture<LLMCacheManageResultPayload> future, long deadlineMillis, String uid) {
    }
}
