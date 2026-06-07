package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.libs.rag.RagSearchResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

final class VectorStore {

    private final String uid;
    private final List<Entry> entries = new ArrayList<>();

    VectorStore(String uid) {
        this.uid = uid;
    }

    synchronized boolean addAll(List<String> newTexts, List<float[]> newVectors) {
        if (newTexts.size() != newVectors.size()) {
            throw new IllegalArgumentException("Texts and vectors size mismatch");
        }
        boolean changed = false;
        for (int i = 0; i < newTexts.size(); i++) {
            String text = newTexts.get(i);
            if (text == null || text.isBlank()) {
                continue;
            }
            float[] vector = newVectors.get(i);
            int existingIndex = indexOf(text);
            if (existingIndex >= 0) {
                Entry existing = entries.get(existingIndex);
                if (!Arrays.equals(existing.vector(), vector)) {
                    entries.set(existingIndex, new Entry(text, copyVector(vector)));
                    changed = true;
                }
            } else {
                entries.add(new Entry(text, copyVector(vector)));
                changed = true;
            }
        }
        return changed;
    }

    synchronized boolean remove(String content) {
        if (content != null) {
            return entries.removeIf(entry -> entry.text().equals(content));
        }
        return false;
    }

    List<RagSearchResult> search(float[] queryVector, int topK, float threshold) {
        List<Entry> snapshot = snapshot();
        if (snapshot.isEmpty() || queryVector == null) {
            return List.of();
        }

        List<RagSearchResult> scored = new ArrayList<>();
        for (Entry entry : snapshot) {
            float score = VectorMath.cosineSimilarity(queryVector, entry.vector());
            if (score >= threshold) {
                scored.add(new RagSearchResult(entry.text(), score));
            }
        }

        return scored.stream()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(topK)
                .collect(Collectors.toList());
    }

    synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    synchronized boolean containsText(String text) {
        return text != null && indexOf(text) >= 0;
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized List<String> getTexts() {
        return entries.stream().map(Entry::text).toList();
    }

    synchronized List<float[]> getVectors() {
        return entries.stream().map(entry -> copyVector(entry.vector())).toList();
    }

    String getUid() {
        return uid;
    }

    private synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    private int indexOf(String text) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).text().equals(text)) {
                return i;
            }
        }
        return -1;
    }

    private static float[] copyVector(float[] vector) {
        return vector == null ? null : Arrays.copyOf(vector, vector.length);
    }

    private record Entry(String text, float[] vector) {
    }
}
