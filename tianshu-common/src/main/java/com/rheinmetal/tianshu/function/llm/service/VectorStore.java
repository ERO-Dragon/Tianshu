package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.libs.rag.RagSearchResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class VectorStore {

    private final String uid;
    private final List<Entry> entries = new ArrayList<>();

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
            return true;
        }
        entries.add(next);
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
        return true;
    }

    synchronized boolean deleteEntry(String entryId) {
        String id = normalizeEntryId(entryId);
        return !id.isBlank() && entries.removeIf(entry -> entry.entryId().equals(id));
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

    List<RagCacheManager.RagEntrySearchResult> searchEntries(float[] queryVector, String queryText, int topK, float threshold) {
        List<Entry> snapshot = snapshot();
        if (snapshot.isEmpty() || queryVector == null || queryVector.length == 0) {
            return List.of();
        }

        int effectiveTopK = topK > 0 ? topK : 4;
        float effectiveThreshold = threshold > 0f && threshold <= 1f ? threshold : 0.7f;
        List<ScoredEntry> scored = new ArrayList<>();
        for (Entry entry : snapshot) {
            float vectorScore = entry.vector() == null || entry.vector().length == 0 ? 0f : VectorMath.cosineSimilarity(queryVector, entry.vector());
            float lexicalScore = lexicalScore(queryText, entry.content());
            float score = entry.content().isBlank() ? vectorScore : Math.max(vectorScore, lexicalScore);
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
        return searchEntries(queryVector, queryText, topK, threshold).stream()
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

    private static float lexicalScore(String queryText, String content) {
        Set<String> queryTerms = terms(queryText);
        if (queryTerms.isEmpty() || content == null || content.isBlank()) {
            return 0f;
        }
        Set<String> contentTerms = terms(content);
        if (contentTerms.isEmpty()) {
            return 0f;
        }
        int hits = 0;
        for (String term : queryTerms) {
            if (contentTerms.contains(term)) {
                hits++;
            }
        }
        return hits <= 0 ? 0f : (float) hits / (float) queryTerms.size();
    }

    private static Set<String> terms(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String normalized = text.toLowerCase();
        String[] parts = normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsIdeographic}]+");
        Set<String> terms = new LinkedHashSet<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                terms.add(part);
            }
        }
        return terms;
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
