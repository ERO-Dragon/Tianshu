package com.rheinmetal.tianshu.function.llm.runtime;

public record LlmMtpCapabilitySnapshot(
        boolean supported,
        int mtpLayerCount,
        boolean calibrated,
        int recommendedDraftMax,
        LlmMtpTrialSnapshot bestTrial
) {
    public LlmMtpCapabilitySnapshot {
        mtpLayerCount = Math.max(0, mtpLayerCount);
        recommendedDraftMax = Math.max(0, recommendedDraftMax);
    }

    public static LlmMtpCapabilitySnapshot unsupported() {
        return new LlmMtpCapabilitySnapshot(false, 0, false, 0, null);
    }
}
