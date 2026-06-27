package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;
import java.util.Map;

public record PresenceContextSnapshotPayload(
        String requestId,
        boolean success,
        List<FactPayload> facts,
        String errorCode,
        String errorMessage
) implements ITianshuPayload {
    public PresenceContextSnapshotPayload {
        requestId = clean(requestId, "presence.context.query");
        facts = facts == null ? List.of() : List.copyOf(facts.stream()
                .filter(fact -> fact != null && !fact.isEmpty())
                .toList());
        errorCode = clean(errorCode, "");
        errorMessage = clean(errorMessage, "");
    }

    public static PresenceContextSnapshotPayload success(String requestId, List<FactPayload> facts) {
        return new PresenceContextSnapshotPayload(requestId, true, facts, "", "");
    }

    public static PresenceContextSnapshotPayload failed(String requestId, String errorCode, String errorMessage) {
        return new PresenceContextSnapshotPayload(requestId, false, List.of(), errorCode, errorMessage);
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    public record FactPayload(
            String factId,
            String text,
            int priority,
            String source,
            String subject,
            List<String> tags,
            long updatedAtMillis,
            long ttlMillis,
            Map<String, String> nativeValues
    ) implements ITianshuPayload {
        public FactPayload(
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

        public FactPayload {
            factId = clean(factId, "presence.fact.unknown");
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

        public boolean isEmpty() {
            return text.isBlank() && nativeValues.isEmpty();
        }
    }
}
