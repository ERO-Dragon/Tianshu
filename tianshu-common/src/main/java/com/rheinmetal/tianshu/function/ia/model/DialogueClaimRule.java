package com.rheinmetal.tianshu.function.ia.model;

import java.util.List;

public record DialogueClaimRule(
        String ruleId,
        DialogueClaimOperator operator,
        List<DialogueClaimCondition> conditions,
        double score,
        double confidence,
        boolean exclusive
) {
    public DialogueClaimRule {
        ruleId = sanitize(ruleId);
        operator = operator == null ? DialogueClaimOperator.ALL : operator;
        conditions = conditions == null ? List.of() : List.copyOf(conditions.stream().filter(condition -> condition != null).toList());
        score = clamp(score);
        confidence = clamp(confidence);
    }

    public static DialogueClaimRule any(String ruleId, double score, double confidence, DialogueClaimCondition... conditions) {
        return new DialogueClaimRule(ruleId, DialogueClaimOperator.ANY, List.of(conditions), score, confidence, false);
    }

    public static DialogueClaimRule all(String ruleId, double score, double confidence, DialogueClaimCondition... conditions) {
        return new DialogueClaimRule(ruleId, DialogueClaimOperator.ALL, List.of(conditions), score, confidence, false);
    }

    public DialogueClaimRule asExclusive() {
        return new DialogueClaimRule(ruleId, operator, conditions, score, confidence, true);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
