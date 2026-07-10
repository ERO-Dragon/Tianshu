package com.rheinmetal.tianshu.function.llm.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Bm25Index {

    private static final double K1 = 1.2D;
    private static final double B = 0.75D;

    private final RagTextAnalyzer analyzer;
    private final Map<String, Document> documents = new HashMap<>();
    private final Map<String, Map<String, Integer>> postings = new HashMap<>();
    private int totalDocumentLength;

    Bm25Index(RagTextAnalyzer analyzer) {
        this.analyzer = analyzer == null ? DefaultRagTextAnalyzer.INSTANCE : analyzer;
    }

    synchronized void upsert(String entryId, String content) {
        String cleanEntryId = entryId == null ? "" : entryId.trim();
        if (cleanEntryId.isBlank()) {
            return;
        }
        delete(cleanEntryId);

        List<String> terms = analyzer.analyze(content);
        if (terms.isEmpty()) {
            return;
        }

        Map<String, Integer> termFrequency = new HashMap<>();
        for (String term : terms) {
            termFrequency.merge(term, 1, Integer::sum);
        }

        Document document = new Document(termFrequency, terms.size());
        documents.put(cleanEntryId, document);
        totalDocumentLength += document.length();
        for (Map.Entry<String, Integer> term : termFrequency.entrySet()) {
            postings.computeIfAbsent(term.getKey(), ignored -> new HashMap<>()).put(cleanEntryId, term.getValue());
        }
    }

    synchronized void delete(String entryId) {
        String cleanEntryId = entryId == null ? "" : entryId.trim();
        Document removed = documents.remove(cleanEntryId);
        if (removed == null) {
            return;
        }
        totalDocumentLength -= removed.length();
        for (String term : removed.termFrequency().keySet()) {
            Map<String, Integer> termPostings = postings.get(term);
            if (termPostings == null) {
                continue;
            }
            termPostings.remove(cleanEntryId);
            if (termPostings.isEmpty()) {
                postings.remove(term);
            }
        }
    }

    synchronized Map<String, Double> score(String queryText) {
        List<String> queryTerms = analyzer.analyze(queryText);
        if (queryTerms.isEmpty() || documents.isEmpty()) {
            return Map.of();
        }

        Map<String, Double> rawScores = new HashMap<>();
        for (String term : uniqueTerms(queryTerms)) {
            Map<String, Integer> termPostings = postings.get(term);
            if (termPostings == null || termPostings.isEmpty()) {
                continue;
            }

            double idf = idf(termPostings.size());
            for (Map.Entry<String, Integer> posting : termPostings.entrySet()) {
                Document document = documents.get(posting.getKey());
                if (document == null || document.length() <= 0) {
                    continue;
                }
                double termScore = idf * bm25TermScore(posting.getValue(), document.length());
                rawScores.merge(posting.getKey(), termScore, Double::sum);
            }
        }

        return normalize(rawScores);
    }

    private double bm25TermScore(int termFrequency, int documentLength) {
        double avgDocumentLength = documents.isEmpty() ? 0D : (double) totalDocumentLength / documents.size();
        if (avgDocumentLength <= 0D) {
            return 0D;
        }
        double denominator = termFrequency + K1 * (1D - B + B * documentLength / avgDocumentLength);
        return denominator <= 0D ? 0D : termFrequency * (K1 + 1D) / denominator;
    }

    private double idf(int documentFrequency) {
        int documentCount = documents.size();
        return Math.log(1D + (documentCount - documentFrequency + 0.5D) / (documentFrequency + 0.5D));
    }

    private static List<String> uniqueTerms(List<String> terms) {
        return terms.stream().distinct().toList();
    }

    private static Map<String, Double> normalize(Map<String, Double> rawScores) {
        if (rawScores.isEmpty()) {
            return Map.of();
        }
        double max = rawScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0D);
        if (max <= 0D || Double.isNaN(max) || Double.isInfinite(max)) {
            return Map.of();
        }
        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Double> entry : rawScores.entrySet()) {
            double value = entry.getValue() / max;
            if (value > 0D && !Double.isNaN(value) && !Double.isInfinite(value)) {
                normalized.put(entry.getKey(), Math.min(1D, value));
            }
        }
        return normalized;
    }

    private record Document(Map<String, Integer> termFrequency, int length) {
        private Document {
            termFrequency = termFrequency == null ? Map.of() : Map.copyOf(termFrequency);
            length = Math.max(0, length);
        }
    }
}
