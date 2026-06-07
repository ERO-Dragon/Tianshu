package com.rheinmetal.tianshu.function.ia.model;

import java.util.ArrayList;
import java.util.List;

public record DialogueClaimProfile(
        DialogueClaimMode mode,
        List<DialogueClaimRule> rules
) {
    public static final DialogueClaimProfile FALLBACK_ONLY = new DialogueClaimProfile(DialogueClaimMode.FALLBACK_ONLY, List.of());
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
            legacyRules.add(DialogueClaimRule.any(
                    "legacy.hotword",
                    0.35D,
                    0.35D,
                    new DialogueClaimCondition(DialogueClaimConditionType.HOTWORD, supportedIntents, "")
            ));
        }
        if (notEmpty(supportedItemIds)) {
            legacyRules.add(DialogueClaimRule.any(
                    "legacy.item",
                    0.3D,
                    0.25D,
                    new DialogueClaimCondition(DialogueClaimConditionType.MATCHED_ITEM, supportedItemIds, ""),
                    new DialogueClaimCondition(DialogueClaimConditionType.HELD_ITEM, supportedItemIds, ""),
                    new DialogueClaimCondition(DialogueClaimConditionType.CONTEXT_ITEM, supportedItemIds, "")
            ));
        }
        if (notEmpty(supportedEntityTypes)) {
            legacyRules.add(DialogueClaimRule.any(
                    "legacy.entity",
                    0.25D,
                    0.25D,
                    new DialogueClaimCondition(DialogueClaimConditionType.MATCHED_ENTITY, supportedEntityTypes, ""),
                    new DialogueClaimCondition(DialogueClaimConditionType.CROSSHAIR_ENTITY, supportedEntityTypes, "")
            ));
        }
        if (legacyRules.isEmpty()) {
            return FALLBACK_ONLY;
        }
        return rules(legacyRules);
    }

    private static boolean notEmpty(List<String> values) {
        return values != null && values.stream().anyMatch(value -> value != null && !value.isBlank());
    }
}
