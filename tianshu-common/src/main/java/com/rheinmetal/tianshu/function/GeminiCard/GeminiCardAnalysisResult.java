package com.rheinmetal.tianshu.function.GeminiCard;

public record GeminiCardAnalysisResult(String text, boolean unavailable) {
    public static GeminiCardAnalysisResult unavailableResult() {
        return new GeminiCardAnalysisResult("", true);
    }
}
