package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.libs.rag.RagSearchResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

final class VectorStore {

    private final String uid;
    private final List<String> texts = Collections.synchronizedList(new ArrayList<>());
    private final List<float[]> vectors = Collections.synchronizedList(new ArrayList<>());

    VectorStore(String uid) {
        this.uid = uid;
    }

    void addAll(List<String> newTexts, List<float[]> newVectors) {
        if (newTexts.size() != newVectors.size()) {
            throw new IllegalArgumentException("Texts and vectors size mismatch");
        }
        for (int i = 0; i < newTexts.size(); i++) {
            texts.add(newTexts.get(i));
            vectors.add(newVectors.get(i));
        }
    }

    void remove(String content) {
        int index = texts.indexOf(content);
        if (index >= 0) {
            texts.remove(index);
            vectors.remove(index);
        }
    }

    List<RagSearchResult> search(float[] queryVector, int topK, float threshold) {
        if (vectors.isEmpty() || queryVector == null) {
            return List.of();
        }

        List<RagSearchResult> scored = new ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) {
            float score = VectorMath.cosineSimilarity(queryVector, vectors.get(i));
            if (score >= threshold) {
                scored.add(new RagSearchResult(texts.get(i), score));
            }
        }

        return scored.stream()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(topK)
                .collect(Collectors.toList());
    }

    boolean isEmpty() {
        return texts.isEmpty();
    }

    int size() {
        return texts.size();
    }

    List<String> getTexts() {
        return new ArrayList<>(texts);
    }

    List<float[]> getVectors() {
        return new ArrayList<>(vectors);
    }

    String getUid() {
        return uid;
    }
}
