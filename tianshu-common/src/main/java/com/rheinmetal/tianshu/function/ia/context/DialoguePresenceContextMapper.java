package com.rheinmetal.tianshu.function.ia.context;

import com.rheinmetal.tianshu.protocol.PresenceContextFactIds;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DialoguePresenceContextMapper {
    public DialogueContextFrame toFrame(String playerId, PresenceContextSnapshotPayload payload) {
        if (payload == null || !payload.success()) {
            return DialogueContextFrame.empty(playerId);
        }
        Map<String, String> nativeValues = new LinkedHashMap<>();
        Map<String, String> facts = new LinkedHashMap<>();
        for (PresenceContextSnapshotPayload.FactPayload fact : payload.facts()) {
            if (fact == null) {
                continue;
            }
            nativeValues.putAll(fact.nativeValues());
            if (!fact.factId().isBlank() && !fact.text().isBlank()) {
                facts.put(fact.factId(), fact.text());
            }
        }

        String effectivePlayerId = firstText(playerId, nativeValues.get("playerId"));
        DialogueEntityRef crosshairRef = crosshairRef(nativeValues);
        DialogueInteractionHints hints = new DialogueInteractionHints(
                value(nativeValues, "heldItemId"),
                crosshairRef != null,
                booleanValue(nativeValues, "interactionKeyDown"),
                booleanValue(nativeValues, "sneaking"),
                doubleValue(nativeValues, "crosshairTargetDistance"),
                List.of()
        );
        DialogueContextSnapshot snapshot = new DialogueContextSnapshot(
                effectivePlayerId,
                value(nativeValues, "dimensionId"),
                crosshairRef == null ? List.of() : List.of(crosshairRef),
                splitList(nativeValues.get("equippedItemIds")),
                facts
        );
        return new DialogueContextFrame(hints, snapshot);
    }

    public List<String> defaultFactIds() {
        return List.of(PresenceContextFactIds.INTERACTION_CONTEXT);
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
