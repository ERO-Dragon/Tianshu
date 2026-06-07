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
        List<String> validTexts = validTexts(texts);
        if (uid == null || uid.isBlank() || validTexts.isEmpty()) {
            return;
        }

        try {
            VectorStore store = stores.computeIfAbsent(uid, k -> new VectorStore(uid));
            List<String> textsToIndex = validTexts.stream()
                    .filter(text -> !store.containsText(text))
                    .toList();
            if (textsToIndex.isEmpty()) {
                return;
            }

            float[][] vectors = embeddingService.embed(textsToIndex);
            if (vectors == null || vectors.length != textsToIndex.size()) {
                env.warn("[RAG] Embedding result size mismatch for uid: " + uid);
                return;
            }

            List<String> filteredTexts = new ArrayList<>();
            List<float[]> filteredVectors = new ArrayList<>();

            for (int i = 0; i < textsToIndex.size(); i++) {
                String text = textsToIndex.get(i);
                float[] vector = vectors[i];
                if (isUsableVector(vector)) {
                    filteredTexts.add(text);
                    filteredVectors.add(VectorMath.normalize(vector));
                }
            }

            if (store.addAll(filteredTexts, filteredVectors)) {
                env.info("[RAG] Indexed " + filteredTexts.size() + " vectors for uid: " + uid);
            }
        } catch (Exception e) {
            env.error("[RAG] Failed to index texts for uid: " + uid, e);
        }
    }

    @Override
    public List<RagSearchResult> search(String uid, String queryText, int topK, float threshold) {
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
            return store.search(VectorMath.normalize(queryVector), effectiveTopK, effectiveThreshold);
        } catch (Exception e) {
            env.error("[RAG] Failed to search for uid: " + uid, e);
            return List.of();
        }
    }

    @Override
    public void evict(String uid) {
        if (uid == null || uid.isBlank()) {
            return;
        }
        VectorStore removed = stores.remove(uid);
        if (removed != null) {
            env.info("[RAG] Evicted all vectors for uid: " + uid);
        }
    }

    @Override
    public void evict(String uid, String content) {
        if (uid == null || uid.isBlank() || content == null) {
            return;
        }
        VectorStore store = stores.get(uid);
        if (store != null) {
            if (store.remove(content)) {
                env.info("[RAG] Evicted content from uid: " + uid);
            }
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

    private static List<String> validTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return texts.stream()
                .filter(text -> text != null && !text.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
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
}
