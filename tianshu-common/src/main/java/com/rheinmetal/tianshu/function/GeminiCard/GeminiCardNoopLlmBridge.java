package com.rheinmetal.tianshu.function.GeminiCard;

public final class GeminiCardNoopLlmBridge implements GeminiCardLlmBridge {
    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public GeminiCardAnalysisResult requestDifferenceAnalysis(GeminiCardAnalysisRequest request) {
        return GeminiCardAnalysisResult.unavailableResult();
    }
}
