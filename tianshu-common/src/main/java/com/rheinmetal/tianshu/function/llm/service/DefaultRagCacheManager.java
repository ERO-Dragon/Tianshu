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
    public void index(String uid, List<String> texts) {
        if (uid == null || texts == null || texts.isEmpty()) {
            return;
        }

        try {
            float[][] vectors = embeddingService.embed(texts);
            VectorStore store = stores.computeIfAbsent(uid, k -> new VectorStore(uid));

            List<String> filteredTexts = new ArrayList<>();
            List<float[]> filteredVectors = new ArrayList<>();

            for (int i = 0; i < texts.size(); i++) {
                String text = texts.get(i);
                float[] vector = vectors[i];
                if (text != null && !text.isBlank() && vector != null && vector.length > 0) {
                    filteredTexts.add(text);
                    filteredVectors.add(VectorMath.normalize(vector));
                }
            }

            store.addAll(filteredTexts, filteredVectors);
            env.info("[RAG] Indexed " + filteredTexts.size() + " vectors for uid: " + uid);
        } catch (Exception e) {
            env.error("[RAG] Failed to index texts for uid: " + uid, e);
        }
    }

    @Override
    public List<RagSearchResult> search(String uid, String queryText, int topK, float threshold) {
        if (uid == null || queryText == null || queryText.isBlank()) {
            return List.of();
        }

        try {
            float[] queryVector = embeddingService.embed(queryText);
            VectorStore store = stores.get(uid);
            if (store == null || store.isEmpty()) {
                return List.of();
            }

            int effectiveTopK = topK > 0 ? topK : DEFAULT_TOP_K;
            float effectiveThreshold = threshold > 0f && threshold <= 1f ? threshold : DEFAULT_THRESHOLD;
            return store.search(VectorMath.normalize(queryVector), effectiveTopK, effectiveThreshold);
        } catch (Exception e) {
            env.error("[RAG] Failed to search for uid: " + uid, e);
            return List.of();
        }
    }

    @Override
    public void evict(String uid) {
        VectorStore removed = stores.remove(uid);
        if (removed != null) {
            env.info("[RAG] Evicted all vectors for uid: " + uid);
        }
    }

    @Override
    public void evict(String uid, String content) {
        VectorStore store = stores.get(uid);
        if (store != null) {
            store.remove(content);
            env.info("[RAG] Evicted content from uid: " + uid);
        }
    }

    @Override
    public boolean hasCache(String uid) {
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
}
