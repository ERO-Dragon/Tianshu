package com.rheinmetal.tianshu.function.llm.runtime;

public record LlmMtpCalibrationRequest(
        Integer maxDraftMax,
        Integer maxTokens,
        Integer targetPromptTokens
) {
    public static LlmMtpCalibrationRequest defaults() {
        return new LlmMtpCalibrationRequest(null, null, null);
    }
}
