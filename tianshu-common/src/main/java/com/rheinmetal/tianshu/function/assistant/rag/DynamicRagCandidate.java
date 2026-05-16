package com.rheinmetal.tianshu.function.assistant.rag;

import java.util.List;

public record DynamicRagCandidate(
        String candidateId,
        String text,
        int priority,
        String source,
        DynamicRagSourceKind sourceKind,
        String subject,
        List<String> tags,
        long updatedAt,
        long ttlMillis,
        DynamicRagExposure exposure
) {
    public DynamicRagCandidate(String text, int priority, String source) {
        this("rag.dynamic.unknown", text, priority, source, DynamicRagSourceKind.UNKNOWN, "", List.of(), System.currentTimeMillis(), 0L, DynamicRagExposure.INTERNAL_ONLY);
    }

    public DynamicRagCandidate {
        candidateId = normalize(candidateId, "rag.dynamic.unknown");
        text = text == null ? "" : text.trim();
        priority = Math.max(0, Math.min(100, priority));
        source = normalize(source, "unknown");
        sourceKind = sourceKind == null ? DynamicRagSourceKind.UNKNOWN : sourceKind;
        subject = subject == null ? "" : subject.trim();
        tags = tags == null ? List.of() : List.copyOf(tags);
        updatedAt = updatedAt <= 0L ? System.currentTimeMillis() : updatedAt;
        ttlMillis = Math.max(0L, ttlMillis);
        exposure = exposure == null ? DynamicRagExposure.INTERNAL_ONLY : exposure;
    }

    public boolean isEmpty() {
        return text.isBlank();
    }

    public boolean isExpired(long now) {
        return ttlMillis > 0L && updatedAt + ttlMillis <= now;
    }

    public boolean shouldIncludeInRequestPackage() {
        return exposure == DynamicRagExposure.REQUEST_DYNAMIC_RAG;
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
