package com.rheinmetal.tianshu.function.presence.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.payload.FactField;

import java.util.List;

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
     * {@code fields} is the whole data surface: there is no rendered sentence and no flat mirror on this
     * contract, because a sentence is one particular consumer's view of the data and a flat mirror is the same
     * view in strings. A consumer converts the typed values it needs, where it needs them.
     */
    public record FactPayload(
            String factId,
            List<FactField> fields,
            int priority,
            String source,
            String subject,
            List<String> tags,
            long updatedAtMillis,
            long ttlMillis
    ) implements ITianshuPayload {
        public FactPayload {
            factId = clean(factId, "presence.fact.unknown");
            fields = fields == null ? List.of() : List.copyOf(fields.stream()
                    .filter(field -> field != null && !field.blank())
                    .toList());
            priority = Math.max(0, Math.min(100, priority));
            source = clean(source, "presence");
            subject = clean(subject, "");
            tags = tags == null ? List.of() : List.copyOf(tags.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .toList());
            updatedAtMillis = updatedAtMillis <= 0L ? System.currentTimeMillis() : updatedAtMillis;
            ttlMillis = Math.max(0L, ttlMillis);
        }

        /** A fact with no fields says nothing, so it is dropped from the snapshot. */
        public boolean isEmpty() {
            return fields.isEmpty();
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
