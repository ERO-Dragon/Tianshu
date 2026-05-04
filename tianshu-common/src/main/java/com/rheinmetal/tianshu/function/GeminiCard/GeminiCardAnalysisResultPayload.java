package com.rheinmetal.tianshu.function.GeminiCard;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record GeminiCardAnalysisResultPayload(
        String semanticKey,
        GeminiCardAnalysisResult result
) implements ITianshuPayload {
    public GeminiCardAnalysisResultPayload {
        if (semanticKey == null) semanticKey = "";
        if (result == null) result = GeminiCardAnalysisResult.unavailableResult();
    }
}
