package com.rheinmetal.tianshu.function.llm.runtime;

public record LlmMtpTrialSnapshot(
        int draftMax,
        boolean success,
        String errorMessage,
        long promptTokens,
        long generatedTokens,
        long draftedTokens,
        long acceptedDraftTokens,
        double acceptanceRate,
        double tokensPerSecond
) {
    public LlmMtpTrialSnapshot {
        draftMax = Math.max(0, draftMax);
        errorMessage = errorMessage == null ? "" : errorMessage.trim();
        promptTokens = Math.max(0L, promptTokens);
        generatedTokens = Math.max(0L, generatedTokens);
        draftedTokens = Math.max(0L, draftedTokens);
        acceptedDraftTokens = Math.max(0L, acceptedDraftTokens);
        acceptanceRate = Math.max(0.0D, acceptanceRate);
        tokensPerSecond = Math.max(0.0D, tokensPerSecond);
    }
}
