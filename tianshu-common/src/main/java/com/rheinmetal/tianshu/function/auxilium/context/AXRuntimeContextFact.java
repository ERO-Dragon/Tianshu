package com.rheinmetal.tianshu.function.auxilium.context;

import java.util.List;
import java.util.Map;

public record AXRuntimeContextFact(
        String factId,
        String text,
        int priority,
        String source,
        String subject,
        List<String> tags,
        long updatedAtMillis,
        long ttlMillis,
        Map<String, String> nativeValues
) {
    public AXRuntimeContextFact(
            String factId,
            String text,
            int priority,
            String source,
            String subject,
            List<String> tags,
            long updatedAtMillis,
            long ttlMillis
    ) {
        this(factId, text, priority, source, subject, tags, updatedAtMillis, ttlMillis, Map.of());
    }

    public AXRuntimeContextFact {
        factId = clean(factId, "runtime.fact.unknown");
        text = clean(text, "");
        priority = Math.max(0, Math.min(100, priority));
        source = clean(source, "presence");
        subject = clean(subject, "");
        tags = tags == null ? List.of() : List.copyOf(tags.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList());
        updatedAtMillis = updatedAtMillis <= 0L ? System.currentTimeMillis() : updatedAtMillis;
        ttlMillis = Math.max(0L, ttlMillis);
        nativeValues = nativeValues == null ? Map.of() : Map.copyOf(nativeValues.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank()
                        && entry.getValue() != null && !entry.getValue().isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().trim(),
                        entry -> entry.getValue().trim(),
                        (first, second) -> second
                )));
    }

    public static AXRuntimeContextFact of(String text, int priority, String source) {
        return new AXRuntimeContextFact(
                "runtime.fact.inline",
                text,
                priority,
                source,
                "",
                List.of(),
                System.currentTimeMillis(),
                0L
        );
    }

    public boolean isEmpty() {
        return text.isBlank() && nativeValues.isEmpty();
    }

    public boolean isExpired(long nowMillis) {
        return ttlMillis > 0L && updatedAtMillis + ttlMillis <= nowMillis;
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
