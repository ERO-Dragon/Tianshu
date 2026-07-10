package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.libs.rag.RagSearchResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class VectorStore {

    private static final double VECTOR_WEIGHT = 0.70D;
    private static final double BM25_WEIGHT = 0.30D;

    private final String uid;
    private final List<Entry> entries = new ArrayList<>();
    private final Bm25Index bm25Index = new Bm25Index(DefaultRagTextAnalyzer.INSTANCE);

    VectorStore(String uid) {
        this.uid = uid == null ? "" : uid;
    }

    synchronized boolean upsert(String entryId, String content, float[] vector) {
        String id = normalizeEntryId(entryId);
        if (id.isBlank()) {
            return false;
        }
        Entry next = new Entry(id, normalizeContent(content), copyVector(vector));
        int index = indexOfEntryId(id);
        if (index >= 0) {
            Entry existing = entries.get(index);
            if (existing.equals(next)) {
                return false;
            }
            entries.set(index, next);
            bm25Index.upsert(next.entryId(), next.content());
            return true;
        }
        entries.add(next);
        bm25Index.upsert(next.entryId(), next.content());
        return true;
    }

    synchronized boolean patch(String entryId, String content, float[] vector, boolean updateContent, boolean updateVector) {
        String id = normalizeEntryId(entryId);
        if (id.isBlank()) {
            return false;
        }
        int index = indexOfEntryId(id);
        if (index < 0) {
            return upsert(id, updateContent ? content : "", updateVector ? vector : null);
        }
        Entry existing = entries.get(index);
        Entry next = new Entry(
                existing.entryId(),
                updateContent ? normalizeContent(content) : existing.content(),
                updateVector ? copyVector(vector) : existing.vector()
        );
        if (existing.equals(next)) {
            return false;
        }
        entries.set(index, next);
        if (updateContent) {
            bm25Index.upsert(next.entryId(), next.content());
        }
        return true;
    }

    synchronized boolean deleteEntry(String entryId) {
        String id = normalizeEntryId(entryId);
        boolean removed = !id.isBlank() && entries.removeIf(entry -> entry.entryId().equals(id));
        if (removed) {
            bm25Index.delete(id);
        }
        return removed;
    }

    synchronized boolean hasEntry(String entryId) {
        return indexOfEntryId(entryId) >= 0;
    }

    synchronized EntrySnapshot getEntry(String entryId) {
        int index = indexOfEntryId(entryId);
        if (index < 0) {
            return null;
        }
        Entry entry = entries.get(index);
        return new EntrySnapshot(entry.entryId(), entry.content(), copyVector(entry.vector()));
    }

    synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    synchronized int size() {
        return entries.size();
    }

    Map<String, Double> bm25Scores(String queryText) {
        return bm25Index.score(queryText);
    }

    List<RagCacheManager.RagEntrySearchResult> searchEntries(float[] queryVector, Map<String, Double> bm25Scores, int topK, float threshold) {
        List<Entry> snapshot = snapshot();
        if (snapshot.isEmpty()) {
            return List.of();
        }

        int effectiveTopK = topK > 0 ? topK : 4;
        float effectiveThreshold = threshold > 0f && threshold <= 1f ? threshold : 0.7f;
        List<ScoredEntry> scored = new ArrayList<>();
        for (Entry entry : snapshot) {
            double vectorScore = vectorScore(queryVector, entry.vector());
            double bm25Score = bm25Scores == null ? 0D : bm25Scores.getOrDefault(entry.entryId(), 0D);
            double score = hybridScore(vectorScore, bm25Score);
            if (score >= effectiveThreshold) {
                scored.add(new ScoredEntry(entry, score));
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .limit(effectiveTopK)
                .map(scoredEntry -> new RagCacheManager.RagEntrySearchResult(scoredEntry.entry().entryId(), scoredEntry.entry().content(), scoredEntry.score()))
                .collect(Collectors.toList());
    }

    List<RagSearchResult> search(float[] queryVector, String queryText, int topK, float threshold) {
        return searchEntries(queryVector, bm25Scores(queryText), topK, threshold).stream()
                .filter(result -> !result.content().isBlank())
                .map(result -> new RagSearchResult(result.content(), result.score()))
                .collect(Collectors.toList());
    }

    synchronized List<EntrySnapshot> getEntries() {
        return entries.stream()
                .map(entry -> new EntrySnapshot(entry.entryId(), entry.content(), copyVector(entry.vector())))
                .toList();
    }

    String getUid() {
        return uid;
    }

    private synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    private int indexOfEntryId(String entryId) {
        String id = normalizeEntryId(entryId);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).entryId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeEntryId(String entryId) {
        return entryId == null ? "" : entryId.trim();
    }

    private static String normalizeContent(String content) {
        return content == null ? "" : content.trim();
    }

    private static float[] copyVector(float[] vector) {
        return vector == null ? null : Arrays.copyOf(vector, vector.length);
    }

    private static double vectorScore(float[] queryVector, float[] entryVector) {
        if (queryVector == null || queryVector.length == 0 || entryVector == null || entryVector.length == 0) {
            return 0D;
        }
        float score = VectorMath.cosineSimilarity(queryVector, entryVector);
        if (Float.isNaN(score) || Float.isInfinite(score)) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, score));
    }

    private static double hybridScore(double vectorScore, double bm25Score) {
        double numerator = 0D;
        double denominator = 0D;
        if (vectorScore > 0D) {
            numerator += vectorScore * VECTOR_WEIGHT;
            denominator += VECTOR_WEIGHT;
        }
        if (bm25Score > 0D) {
            numerator += bm25Score * BM25_WEIGHT;
            denominator += BM25_WEIGHT;
        }
        if (denominator <= 0D) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, numerator / denominator));
    }

    record EntrySnapshot(String entryId, String content, float[] vector) {
    }

    private record Entry(String entryId, String content, float[] vector) {
        private Entry {
            vector = copyVector(vector);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Entry entry)) {
                return false;
            }
            return entryId.equals(entry.entryId())
                    && content.equals(entry.content())
                    && Arrays.equals(vector, entry.vector());
        }

        @Override
        public int hashCode() {
            int result = entryId.hashCode();
            result = 31 * result + content.hashCode();
            result = 31 * result + Arrays.hashCode(vector);
            return result;
        }
    }

    private record ScoredEntry(Entry entry, double score) {
    }
}
