package com.rheinmetal.tianshu.function.llm.inference;

public record LlmRagHit(String source, String uid, double score, String text) {
    public LlmRagHit {
        source = source == null ? "" : source.trim();
        uid = uid == null ? "" : uid.trim();
        score = Double.isNaN(score) || Double.isInfinite(score) ? 0.0D : score;
        text = text == null ? "" : text.trim();
    }

    public boolean memory() {
        return "memory".equals(source) && !uid.isBlank();
    }
}
