package com.rheinmetal.tianshu.protocol.dialogue.model;

import java.util.List;

public record DialogueClaimRule(
        String ruleId,
        DialogueClaimOperator operator,
        List<DialogueClaimCondition> conditions,
        DialogueClaimStrength strength,
        DialogueAttentionDecay decay
) {
    public DialogueClaimRule {
        ruleId = sanitize(ruleId);
        operator = operator == null ? DialogueClaimOperator.ALL : operator;
        conditions = conditions == null ? List.of() : List.copyOf(conditions.stream().filter(condition -> condition != null).toList());
        strength = strength == null ? DialogueClaimStrength.NORMAL : strength;
        decay = decay == null ? DialogueAttentionDecay.FAST : decay;
    }

    public static DialogueClaimRule any(String ruleId, DialogueClaimStrength strength, DialogueAttentionDecay decay, DialogueClaimCondition... conditions) {
        return new DialogueClaimRule(ruleId, DialogueClaimOperator.ANY, List.of(conditions), strength, decay);
    }

    public static DialogueClaimRule all(String ruleId, DialogueClaimStrength strength, DialogueAttentionDecay decay, DialogueClaimCondition... conditions) {
        return new DialogueClaimRule(ruleId, DialogueClaimOperator.ALL, List.of(conditions), strength, decay);
    }

    public static DialogueClaimRule anyNormal(String ruleId, DialogueClaimCondition... conditions) {
        return any(ruleId, DialogueClaimStrength.NORMAL, DialogueAttentionDecay.FAST, conditions);
    }

    public static DialogueClaimRule allNormal(String ruleId, DialogueClaimCondition... conditions) {
        return all(ruleId, DialogueClaimStrength.NORMAL, DialogueAttentionDecay.FAST, conditions);
    }

    public static DialogueClaimRule anyStrong(String ruleId, DialogueAttentionDecay decay, DialogueClaimCondition... conditions) {
        return any(ruleId, DialogueClaimStrength.STRONG, decay, conditions);
    }

    public static DialogueClaimRule allStrong(String ruleId, DialogueAttentionDecay decay, DialogueClaimCondition... conditions) {
        return all(ruleId, DialogueClaimStrength.STRONG, decay, conditions);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
