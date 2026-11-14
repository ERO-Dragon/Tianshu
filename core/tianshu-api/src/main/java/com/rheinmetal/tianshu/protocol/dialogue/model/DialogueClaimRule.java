package com.rheinmetal.tianshu.protocol.dialogue.model;

import java.util.List;

public record DialogueClaimRule(
        String ruleId,
        DialogueClaimOperator operator,
        List<DialogueClaimCondition> conditions
) {
    public DialogueClaimRule {
        ruleId = sanitize(ruleId);
        operator = operator == null ? DialogueClaimOperator.ALL : operator;
        conditions = conditions == null ? List.of() : List.copyOf(conditions.stream().filter(condition -> condition != null).toList());
    }

    public static DialogueClaimRule any(String ruleId, DialogueClaimCondition... conditions) {
        return new DialogueClaimRule(ruleId, DialogueClaimOperator.ANY, List.of(conditions));
    }

    public static DialogueClaimRule all(String ruleId, DialogueClaimCondition... conditions) {
        return new DialogueClaimRule(ruleId, DialogueClaimOperator.ALL, List.of(conditions));
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
