package com.rheinmetal.tianshu.function.ia.context;

import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextFrame;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueEntityRef;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.protocol.payload.FactField;
import com.rheinmetal.tianshu.protocol.payload.FactValue;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a presence fact snapshot into the arbitration context.
 *
 * <p>The snapshot is structured data: presence publishes typed {@code fields} and nothing else. The flat
 * {@code Map<String, String>} this frame is built on therefore has to be produced here — the rule that turns a
 * typed value into a string belongs to the consumer that wants strings, not to the producer that holds the
 * semantics. Records are flattened with dotted names ({@code helmet.itemId}), blank values are dropped so
 * "absent" stays distinguishable from "empty", and {@link FactValue.Number} keeps the one-decimal form the
 * presence rows have always used.
 *
 * <p>{@code DialogueContextSnapshot.facts} stays keyed by fact id; its value is the fact's field summary
 * ({@code name=value;name=value}). Claims no longer read it (the fact condition was removed), but the delivery
 * snapshot keeps it for consumers that display which facts were part of the turn.
 */
public final class DialoguePresenceContextMapper {
    public DialogueContextFrame toFrame(String playerId, PresenceContextSnapshotPayload payload) {
        if (payload == null || !payload.success()) {
            return DialogueContextFrame.empty(playerId);
        }
        Map<String, String> fieldValues = new LinkedHashMap<>();
        Map<String, String> facts = new LinkedHashMap<>();
        for (PresenceContextSnapshotPayload.FactPayload fact : payload.facts()) {
            if (fact == null) {
                continue;
            }
            Map<String, String> flattened = flatten(fact.fields());
            // Payload order decides on a repeated field name (several facts carry dimensionId), exactly as the
            // flat transitional map did before it was removed.
            fieldValues.putAll(flattened);
            if (!fact.factId().isBlank() && !flattened.isEmpty()) {
                facts.put(fact.factId(), summarize(flattened));
            }
        }

        String effectivePlayerId = firstText(playerId, fieldValues.get("playerId"));
        DialogueEntityRef crosshairRef = crosshairRef(fieldValues);
        DialogueInteractionHints hints = new DialogueInteractionHints(
                value(fieldValues, "heldItemId"),
                crosshairRef != null,
                booleanValue(fieldValues, "interactionKeyDown"),
                booleanValue(fieldValues, "sneaking"),
                doubleValue(fieldValues, "crosshairTargetDistance")
        );
        DialogueContextSnapshot snapshot = new DialogueContextSnapshot(
                effectivePlayerId,
                value(fieldValues, "dimensionId"),
                crosshairRef == null ? List.of() : List.of(crosshairRef),
                splitList(fieldValues.get("equippedItemIds")),
                facts
        );
        return new DialogueContextFrame(hints, snapshot, displayNames(fieldValues));
    }

    /**
     * Pairs an id with the display name the snapshot happened to carry for it.
     *
     * <p>The presence snapshot publishes ids and, for some facts, a companion display name ({@code itemId} next to
     * {@code displayName}, {@code dimensionId} without one). There is no fabricated name here: a value is recorded only
     * when the companion field exists and is non-blank, so a consumer that finds no entry falls back to a readable form
     * of the id instead of showing the player a machine identifier.</p>
     *
     * <p>Two companion spellings are recognised, because both occur: {@code <base>DisplayName} (a flat fact field) and
     * {@code <parent>.displayName} (the nested record an equipment slot is flattened into).</p>
     */
    private static Map<String, String> displayNames(Map<String, String> values) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String id = entry.getValue();
            if (key == null || !key.endsWith("Id") || id == null || id.isBlank() || id.indexOf(':') < 0) {
                continue;
            }
            String stem = key.substring(0, key.length() - 2);
            String companion = values.get(stem + "DisplayName");
            if (companion == null || companion.isBlank()) {
                companion = nestedDisplayName(values, stem);
            }
            if (companion != null && !companion.isBlank()) {
                names.putIfAbsent(id.trim(), companion.trim());
            }
        }
        return names;
    }

    /** {@code helmet.itemId} pairs with {@code helmet.displayName}; a flat field has no parent and yields nothing. */
    private static String nestedDisplayName(Map<String, String> values, String stem) {
        int dot = stem.lastIndexOf('.');
        if (dot <= 0) {
            return "";
        }
        return values.getOrDefault(stem.substring(0, dot) + ".displayName", "");
    }

    /** Every field of every fact, as strings; the only place a typed value becomes text on this path. */
    private static Map<String, String> flatten(List<FactField> fields) {
        Map<String, String> values = new LinkedHashMap<>();
        collect("", fields, values);
        return values;
    }

    private static void collect(String prefix, List<FactField> fields, Map<String, String> out) {
        if (fields == null) {
            return;
        }
        for (FactField field : fields) {
            if (field == null || field.blank()) {
                continue;
            }
            String name = prefix.isEmpty() ? field.name() : prefix + "." + field.name();
            FactValue value = field.value();
            if (value instanceof FactValue.Record record) {
                collect(name, record.fields(), out);
                continue;
            }
            String text = textOf(value);
            if (!text.isBlank()) {
                out.put(name, text);
            }
        }
    }

    private static String textOf(FactValue value) {
        if (value instanceof FactValue.Number number) {
            // The presence rows have always carried one decimal ("20.0", "4.0"); the values a consumer compares
            // against were written that way, so the conversion keeps the same text.
            return String.format(Locale.ROOT, "%.1f", number.value());
        }
        if (value instanceof FactValue.Count count) {
            return Integer.toString(count.value());
        }
        if (value instanceof FactValue.Identifier identifier) {
            return identifier.value();
        }
        if (value instanceof FactValue.Text text) {
            return text.value();
        }
        if (value instanceof FactValue.Flag flag) {
            return Boolean.toString(flag.value());
        }
        return "";
    }

    /** {@code name=value;name=value}: the structured content of one fact, in field order. */
    private static String summarize(Map<String, String> fields) {
        StringBuilder summary = new StringBuilder();
        fields.forEach((name, value) -> {
            if (summary.length() > 0) {
                summary.append(';');
            }
            summary.append(name).append('=').append(value);
        });
        return summary.toString();
    }

    private DialogueEntityRef crosshairRef(Map<String, String> values) {
        String entityTypeId = value(values, "crosshairTargetTypeId");
        String entityId = value(values, "crosshairTargetId");
        if (entityTypeId.isBlank() && entityId.isBlank()) {
            return null;
        }
        return new DialogueEntityRef(
                entityId,
                entityTypeId,
                value(values, "crosshairTargetDisplayName"),
                doubleValue(values, "crosshairTargetDistance"),
                true
        );
    }

    private List<String> splitList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split("\\|"))
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private boolean booleanValue(Map<String, String> values, String key) {
        return Boolean.parseBoolean(value(values, key));
    }

    private double doubleValue(Map<String, String> values, String key) {
        try {
            return Double.parseDouble(value(values, key));
        } catch (NumberFormatException ignored) {
            return 0.0D;
        }
    }

    private String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    private String value(Map<String, String> values, String key) {
        if (values == null || key == null) {
            return "";
        }
        String value = values.get(key);
        return value == null ? "" : value.trim();
    }
}
