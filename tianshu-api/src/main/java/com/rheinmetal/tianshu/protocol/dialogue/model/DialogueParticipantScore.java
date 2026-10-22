package com.rheinmetal.tianshu.protocol.dialogue.model;

public record DialogueParticipantScore(
        String participantId,
        double score,
        Source source,
        String reason
) {
    public DialogueParticipantScore {
        if (participantId == null || participantId.isBlank()) {
            throw new IllegalArgumentException("participantId cannot be blank");
        }
        participantId = participantId.trim();
        score = clamp(score);
        source = source == null ? Source.FALLBACK : source;
        reason = reason == null ? "" : reason.trim();
    }

    public enum Source {
        DYNAMIC,
        FALLBACK,
        ATTENTION
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
