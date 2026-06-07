package com.rheinmetal.tianshu.function.ia.model;

import java.util.List;

public record DialogueClaimCondition(
        DialogueClaimConditionType type,
        List<String> values,
        String factKey
) {
    public DialogueClaimCondition {
        type = type == null ? DialogueClaimConditionType.HOTWORD : type;
        values = copyTextList(values);
        factKey = sanitize(factKey);
    }

    public static DialogueClaimCondition hotword(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.HOTWORD, List.of(values), "");
    }

    public static DialogueClaimCondition heldItem(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.HELD_ITEM, List.of(values), "");
    }

    public static DialogueClaimCondition matchedItem(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.MATCHED_ITEM, List.of(values), "");
    }

    public static DialogueClaimCondition matchedEntity(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.MATCHED_ENTITY, List.of(values), "");
    }

    public static DialogueClaimCondition crosshairEntity(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.CROSSHAIR_ENTITY, List.of(values), "");
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

    public static DialogueClaimCondition contextItem(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.CONTEXT_ITEM, List.of(values), "");
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
