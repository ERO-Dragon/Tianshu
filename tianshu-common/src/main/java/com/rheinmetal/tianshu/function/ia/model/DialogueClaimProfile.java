package com.rheinmetal.tianshu.function.ia.model;

import java.util.ArrayList;
import java.util.List;

public record DialogueClaimProfile(
        DialogueClaimMode mode,
        List<DialogueClaimRule> rules
) {
    public static final DialogueClaimProfile DEFAULT_OWNER = new DialogueClaimProfile(DialogueClaimMode.DEFAULT_OWNER, List.of());
    public static final DialogueClaimProfile DISABLED = new DialogueClaimProfile(DialogueClaimMode.DISABLED, List.of());

    public DialogueClaimProfile {
        mode = mode == null ? DialogueClaimMode.RULES : mode;
        rules = rules == null ? List.of() : List.copyOf(rules.stream().filter(rule -> rule != null).toList());
    }

    public static DialogueClaimProfile rules(List<DialogueClaimRule> rules) {
        return new DialogueClaimProfile(DialogueClaimMode.RULES, rules);
    }

    public static DialogueClaimProfile rules(DialogueClaimRule... rules) {
        return rules(List.of(rules));
    }

    public static DialogueClaimProfile legacy(List<String> supportedIntents, List<String> supportedEntityTypes, List<String> supportedItemIds) {
        List<DialogueClaimRule> legacyRules = new ArrayList<>();
        if (notEmpty(supportedIntents)) {
            legacyRules.add(new DialogueClaimRule(
                    "legacy.wake_word",
                    DialogueClaimOperator.ANY,
                    List.of(new DialogueClaimCondition(DialogueClaimConditionType.WAKE_WORD, supportedIntents, "")),
                    DialogueClaimStrength.STRONG,
                    DialogueAttentionDecay.SLOW
            ));
        }
        if (notEmpty(supportedItemIds)) {
            legacyRules.add(new DialogueClaimRule(
                    "legacy.item",
                    DialogueClaimOperator.ANY,
                    List.of(
                            new DialogueClaimCondition(DialogueClaimConditionType.HELD_ITEM, supportedItemIds, ""),
                            new DialogueClaimCondition(DialogueClaimConditionType.EQUIPPED_ITEM, supportedItemIds, "")
                    ),
                    DialogueClaimStrength.NORMAL,
                    DialogueAttentionDecay.FAST
            ));
        }
        if (notEmpty(supportedEntityTypes)) {
            legacyRules.add(new DialogueClaimRule(
                    "legacy.entity",
                    DialogueClaimOperator.ANY,
                    List.of(new DialogueClaimCondition(DialogueClaimConditionType.CROSSHAIR_ENTITY, supportedEntityTypes, "")),
                    DialogueClaimStrength.NORMAL,
                    DialogueAttentionDecay.SLOW
            ));
        }
        if (legacyRules.isEmpty()) {
            return DISABLED;
        }
        return rules(legacyRules);
    }

    private static boolean notEmpty(List<String> values) {
        return values != null && values.stream().anyMatch(value -> value != null && !value.isBlank());
    }
}
