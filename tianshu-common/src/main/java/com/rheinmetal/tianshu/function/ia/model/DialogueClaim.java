package com.rheinmetal.tianshu.function.ia.model;

public record DialogueClaim(String participantId, double score, double confidence, int priority, boolean exclusive, String reason) {
    public DialogueClaim {
        if (participantId == null || participantId.isBlank()) {
            throw new IllegalArgumentException("participantId cannot be blank");
        }
        participantId = participantId.trim();
        score = clamp(score);
        confidence = clamp(confidence);
        reason = reason == null ? "" : reason.trim();
    }

    public static DialogueClaim reject(String participantId, String reason) {
        return new DialogueClaim(participantId, 0.0D, 0.0D, Integer.MIN_VALUE, false, reason);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
