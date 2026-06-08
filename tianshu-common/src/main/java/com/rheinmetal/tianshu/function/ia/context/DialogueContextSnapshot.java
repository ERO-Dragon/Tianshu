package com.rheinmetal.tianshu.function.ia.context;

import java.util.List;
import java.util.Map;

public record DialogueContextSnapshot(String playerId, String dimensionId, List<DialogueEntityRef> entityRefs, List<String> equippedItemIds, Map<String, String> facts) {
    public DialogueContextSnapshot {
        playerId = sanitize(playerId);
        dimensionId = sanitize(dimensionId);
        entityRefs = entityRefs == null ? List.of() : List.copyOf(entityRefs);
        equippedItemIds = equippedItemIds == null ? List.of() : List.copyOf(equippedItemIds.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList());
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }

    public static DialogueContextSnapshot empty(String playerId) {
        return new DialogueContextSnapshot(playerId, "", List.of(), List.of(), Map.of());
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
