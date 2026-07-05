package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class DefaultRagCacheManager implements RagCacheManager {

    private static final int DEFAULT_TOP_K = 4;
    private static final float DEFAULT_THRESHOLD = 0.7f;

    private final IGameEnvironment env;
    private final EmbeddingService embeddingService;
    private final ConcurrentMap<String, VectorStore> stores = new ConcurrentHashMap<>();

    public DefaultRagCacheManager(IGameEnvironment env, EmbeddingService embeddingService) {
        this.env = Objects.requireNonNull(env, "env");
        this.embeddingService = Objects.requireNonNull(embeddingService, "embeddingService");
    }

    @Override
    public void upsert(String uid, String entryId, String content, float[] vector) {
        if (uid == null || uid.isBlank() || entryId == null || entryId.isBlank()) {
            return;
        }
        try {
            VectorStore store = stores.computeIfAbsent(uid, k -> new VectorStore(uid));
            if (isSameContentWithoutVectorChange(store, entryId, content, vector)) {
                return;
            }
            float[] effectiveVector = resolveVector(content, vector);
            if (store.upsert(entryId, content, normalizeVector(effectiveVector))) {
                env.info("[RAG] Upserted entry for uid: " + uid);
            }
        } catch (Exception e) {
            env.error("[RAG] Failed to upsert entry for uid: " + uid, e);
        }
    }

    @Override
    public void patch(String uid, String entryId, String content, float[] vector, boolean updateContent, boolean updateVector) {
        if (uid == null || uid.isBlank() || entryId == null || entryId.isBlank()) {
            return;
        }
        try {
            VectorStore store = stores.computeIfAbsent(uid, k -> new VectorStore(uid));
            float[] effectiveVector = updateVector ? normalizeVector(resolveVector(content, vector)) : null;
            if (store.patch(entryId, content, effectiveVector, updateContent, updateVector)) {
                env.info("[RAG] Patched entry for uid: " + uid);
            }
        } catch (Exception e) {
            env.error("[RAG] Failed to patch entry for uid: " + uid, e);
        }
    }

    @Override
    public void deleteEntry(String uid, String entryId) {
        if (uid == null || uid.isBlank() || entryId == null || entryId.isBlank()) {
            return;
        }
        VectorStore store = stores.get(uid);
        if (store != null && store.deleteEntry(entryId)) {
            env.info("[RAG] Deleted entry from uid: " + uid);
        }
    }

    @Override
    public void clearUid(String uid) {
        if (uid == null || uid.isBlank()) {
            return;
        }
        VectorStore removed = stores.remove(uid);
        if (removed != null) {
            env.info("[RAG] Cleared uid: " + uid);
        }
    }

    @Override
    public boolean hasEntry(String uid, String entryId) {
        if (uid == null || uid.isBlank() || entryId == null || entryId.isBlank()) {
            return false;
        }
        VectorStore store = stores.get(uid);
        return store != null && store.hasEntry(entryId);
    }

    @Override
    public List<RagSearchResult> search(String uid, String queryText, int topK, float threshold) {
        return searchEntries(uid, queryText, topK, threshold).stream()
                .filter(result -> !result.content().isBlank())
                .map(result -> new RagSearchResult(result.content(), result.score()))
                .toList();
    }

    @Override
    public List<RagEntrySearchResult> searchEntries(String uid, String queryText, int topK, float threshold) {
        if (uid == null || uid.isBlank() || queryText == null || queryText.isBlank()) {
            return List.of();
        }

        try {
            float[] queryVector = embeddingService.embed(queryText);
            if (!isUsableVector(queryVector)) {
                return List.of();
            }
            VectorStore store = stores.get(uid);
            if (store == null || store.isEmpty()) {
                return List.of();
            }

            int effectiveTopK = topK > 0 ? topK : DEFAULT_TOP_K;
            float effectiveThreshold = threshold > 0f && threshold <= 1f ? threshold : DEFAULT_THRESHOLD;
            return store.searchEntries(VectorMath.normalize(queryVector), queryText, effectiveTopK, effectiveThreshold);
        } catch (Exception e) {
            env.error("[RAG] Failed to search for uid: " + uid, e);
            return List.of();
        }
    }

    @Override
    public boolean hasCache(String uid) {
        if (uid == null || uid.isBlank()) {
            return false;
        }
        VectorStore store = stores.get(uid);
        return store != null && !store.isEmpty();
    }

    @Override
    public CacheStats getStats() {
        int uidCount = stores.size();
        int totalChunks = stores.values().stream().mapToInt(VectorStore::size).sum();
        return new CacheStats(uidCount, totalChunks, 0L);
    }

    @Override
    public void clear() {
        stores.clear();
        env.info("[RAG] Cleared all caches");
    }

    private float[] resolveVector(String content, float[] vector) throws Exception {
        if (isUsableVector(vector)) {
            return vector;
        }
        if (content == null || content.isBlank()) {
            return null;
        }
        return embeddingService.embed(content);
    }

    private static float[] normalizeVector(float[] vector) {
        return isUsableVector(vector) ? VectorMath.normalize(vector) : null;
    }

    private static boolean isUsableVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            return false;
        }
        for (float value : vector) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSameContentWithoutVectorChange(VectorStore store, String entryId, String content, float[] vector) {
        if (isUsableVector(vector)) {
            return false;
        }
        VectorStore.EntrySnapshot existing = store.getEntry(entryId);
        if (existing == null || !isUsableVector(existing.vector())) {
            return false;
        }
        String nextContent = content == null ? "" : content.trim();
        return existing.content().equals(nextContent);
    }
}
