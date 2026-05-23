package com.rheinmetal.tianshu.function.auxilium.fact;

import java.util.List;
import java.util.Map;

public record RuntimeFact(String factId, String type, String source, String subject, Map<String, String> fields, List<String> tags, int importance, long updatedAt, long ttlMillis, long version) {
    public RuntimeFact {
        factId = normalize(factId, "fact.unknown");
        type = normalize(type, "fact");
        source = normalize(source, "unknown");
        subject = subject == null ? "" : subject.trim();
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        tags = tags == null ? List.of() : List.copyOf(tags);
        importance = Math.max(0, Math.min(100, importance));
        updatedAt = updatedAt <= 0L ? System.currentTimeMillis() : updatedAt;
        ttlMillis = Math.max(0L, ttlMillis);
        version = Math.max(0L, version);
    }

    public boolean isExpired(long now) {
        return ttlMillis > 0L && updatedAt + ttlMillis <= now;
    }

    public boolean isEmpty() {
        return fields.isEmpty() && subject.isBlank();
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
