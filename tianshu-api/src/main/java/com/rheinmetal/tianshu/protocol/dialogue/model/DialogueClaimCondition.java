package com.rheinmetal.tianshu.protocol.dialogue.model;

import java.util.List;

public record DialogueClaimCondition(
        DialogueClaimConditionType type,
        List<String> values,
        String factKey,
        double maxDistance
) {
    public DialogueClaimCondition {
        type = type == null ? DialogueClaimConditionType.WAKE_WORD : type;
        values = copyTextList(values);
        factKey = sanitize(factKey);
        maxDistance = Math.max(0.0D, maxDistance);
    }

    public DialogueClaimCondition(DialogueClaimConditionType type, List<String> values, String factKey) {
        this(type, values, factKey, 0.0D);
    }

    public static DialogueClaimCondition wakeWord(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.WAKE_WORD, List.of(values), "");
    }

    public static DialogueClaimCondition heldItem(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.HELD_ITEM, List.of(values), "");
    }

    public static DialogueClaimCondition equippedItem(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.EQUIPPED_ITEM, List.of(values), "");
    }

    public static DialogueClaimCondition mentionedEntity(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.MENTIONED_ENTITY, List.of(values), "");
    }

    public static DialogueClaimCondition crosshairEntity(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.CROSSHAIR_ENTITY, List.of(values), "");
    }

    public static DialogueClaimCondition nearestEntityWithin(double maxDistance, String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.NEAREST_ENTITY_WITHIN, List.of(values), "", maxDistance);
    }

    public static DialogueClaimCondition crosshairHit() {
        return new DialogueClaimCondition(DialogueClaimConditionType.CROSSHAIR_HIT, List.of(), "");
    }

    public static DialogueClaimCondition interactionKey() {
        return new DialogueClaimCondition(DialogueClaimConditionType.INTERACTION_KEY, List.of(), "");
    }

    public static DialogueClaimCondition sneaking() {
        return new DialogueClaimCondition(DialogueClaimConditionType.SNEAKING, List.of(), "");
    }

    public static DialogueClaimCondition interactionTag(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.INTERACTION_TAG, List.of(values), "");
    }

    public static DialogueClaimCondition contextFact(String factKey, String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.CONTEXT_FACT, List.of(values), factKey);
    }

    private static List<String> copyTextList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList());
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
