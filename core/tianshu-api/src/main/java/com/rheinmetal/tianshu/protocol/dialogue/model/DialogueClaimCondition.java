package com.rheinmetal.tianshu.protocol.dialogue.model;

import java.util.List;

public record DialogueClaimCondition(
        DialogueClaimConditionType type,
        List<String> values
) {
    public DialogueClaimCondition {
        type = type == null ? DialogueClaimConditionType.WAKE_WORD : type;
        values = copyTextList(values);
    }

    public static DialogueClaimCondition wakeWord(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.WAKE_WORD, List.of(values));
    }

    public static DialogueClaimCondition heldItem(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.HELD_ITEM, List.of(values));
    }

    public static DialogueClaimCondition equippedItem(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.EQUIPPED_ITEM, List.of(values));
    }

    public static DialogueClaimCondition mentionedEntity(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.MENTIONED_ENTITY, List.of(values));
    }

    /**
     * Matches when the crosshair is on an entity whose type id is listed; no values means "any entity".
     *
     * <p>That wildcard is why there is no separate "crosshair hit something" condition: the value list is the
     * only thing that distinguishes "aiming at 酒狐" from "aiming at anything".</p>
     */
    public static DialogueClaimCondition crosshairEntity(String... values) {
        return new DialogueClaimCondition(DialogueClaimConditionType.CROSSHAIR_ENTITY, List.of(values));
    }

    public static DialogueClaimCondition interactionKey() {
        return new DialogueClaimCondition(DialogueClaimConditionType.INTERACTION_KEY, List.of());
    }

    public static DialogueClaimCondition sneaking() {
        return new DialogueClaimCondition(DialogueClaimConditionType.SNEAKING, List.of());
    }

    private static List<String> copyTextList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList());
    }
}