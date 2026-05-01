package com.rheinmetal.tianshu.function.GeminiCard;

public interface GeminiCardLlmBridge {
    boolean isConfigured();

    GeminiCardAnalysisResult requestDifferenceAnalysis(GeminiCardAnalysisRequest request);
}
