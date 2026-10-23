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

    /**
     * One requested fact, as structured data.
     *
     * <p><strong>Facts are data, not speech.</strong> This type carries a fact id plus typed fields;
     * presentation is decided by whichever consumer reads it, in its own language, through its own templates.
     * There is no rendered sentence on this contract, because a sentence is one particular consumer's view of
     * the data.
     *
     * <p>{@code nativeValues} is a transitional mirror of the same information in flat string form. It exists so
     * the consumers that still read a flat map keep working while they are migrated to {@code fields}; the
     * producer writes it alongside {@code fields}, and it goes away once the last reader is converted.
     */
    public record FactPayload(
            String factId,
            List<FactField> fields,
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
                List<FactField> fields,
                int priority,
                String source,
                String subject,
                List<String> tags,
                long updatedAtMillis,
                long ttlMillis
        ) {
            this(factId, fields, "", priority, source, subject, tags, updatedAtMillis, ttlMillis, Map.of());
        }

        public FactPayload {
            factId = clean(factId, "presence.fact.unknown");
            fields = fields == null ? List.of() : List.copyOf(fields.stream()
                    .filter(field -> field != null && !field.blank())
                    .toList());
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
            // While the deprecated text is still on the contract, a fact that only has it is not empty yet.
            return fields.isEmpty() && nativeValues.isEmpty() && text.isBlank();
        }

        /** The first field with this name, or {@code null}; convenience for consumers reading one value. */
        public FactField field(String name) {
            if (name == null) {
                return null;
            }
            for (FactField field : fields) {
                if (name.equals(field.name())) {
                    return field;
                }
            }
            return null;
        }
    }
}
