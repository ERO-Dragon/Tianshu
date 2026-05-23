package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.output.MemoryUpdateCandidate;
import com.rheinmetal.tianshu.function.auxilium.output.MemoryUpdateTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MemoryConsolidationPlanner {
    private static final int LONG_TERM_ACCEPT_CONFIDENCE = 80;
    private static final int WORLD_SUMMARY_ACCEPT_CONFIDENCE = 60;
    private static final int MAX_ACCEPTED_PER_TARGET = 8;

    public MemoryConsolidationPlan plan(List<MemoryUpdateCandidate> pending, AXMemorySnapshot currentMemory) {
        if (pending == null || pending.isEmpty()) {
            return new MemoryConsolidationPlan(List.of(), List.of(), List.of());
        }
        Map<String, MemoryUpdateCandidate> deduplicated = new LinkedHashMap<>();
        List<MemoryUpdateCandidate> rejected = new ArrayList<>();
        for (MemoryUpdateCandidate candidate : pending) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            if (alreadyKnown(candidate, currentMemory)) {
                rejected.add(candidate);
                continue;
            }
            String key = candidate.target().name() + "|" + normalizeKey(candidate.text());
            MemoryUpdateCandidate current = deduplicated.get(key);
            if (current == null || candidate.confidence() > current.confidence() || candidate.createdAt() > current.createdAt()) {
                deduplicated.put(key, candidate);
            }
        }
        List<MemoryUpdateCandidate> accepted = new ArrayList<>();
        List<MemoryUpdateCandidate> deferred = new ArrayList<>();
        acceptByTarget(deduplicated.values().stream().filter(candidate -> candidate.target() == MemoryUpdateTarget.LONG_TERM_USER_MEMORY).toList(), LONG_TERM_ACCEPT_CONFIDENCE, accepted, deferred);
        acceptByTarget(deduplicated.values().stream().filter(candidate -> candidate.target() == MemoryUpdateTarget.WORLD_CONVERSATION_SUMMARY).toList(), WORLD_SUMMARY_ACCEPT_CONFIDENCE, accepted, deferred);
        return new MemoryConsolidationPlan(accepted, deferred, rejected);
    }

    private void acceptByTarget(List<MemoryUpdateCandidate> candidates, int minConfidence, List<MemoryUpdateCandidate> accepted, List<MemoryUpdateCandidate> deferred) {
        List<MemoryUpdateCandidate> sorted = candidates.stream()
                .sorted((left, right) -> {
                    int confidence = Integer.compare(right.confidence(), left.confidence());
                    if (confidence != 0) {
                        return confidence;
                    }
                    return Long.compare(right.createdAt(), left.createdAt());
                })
                .toList();
        int acceptedCount = 0;
        for (MemoryUpdateCandidate candidate : sorted) {
            if (candidate.confidence() < minConfidence || acceptedCount >= MAX_ACCEPTED_PER_TARGET) {
                deferred.add(candidate);
                continue;
            }
            accepted.add(candidate);
            acceptedCount++;
        }
    }

    private boolean alreadyKnown(MemoryUpdateCandidate candidate, AXMemorySnapshot currentMemory) {
        if (currentMemory == null) {
            return false;
        }
        String key = normalizeKey(candidate.text());
        if (key.isBlank()) {
            return true;
        }
        if (candidate.target() == MemoryUpdateTarget.LONG_TERM_USER_MEMORY) {
            return currentMemory.longTermUserMemory().stream().map(this::normalizeKey).anyMatch(key::equals);
        }
        if (candidate.target() == MemoryUpdateTarget.WORLD_CONVERSATION_SUMMARY) {
            return currentMemory.conversationSummary().stream().map(this::normalizeKey).anyMatch(key::equals);
        }
        return false;
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
