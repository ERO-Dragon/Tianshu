package com.rheinmetal.tianshu.function.llm.runtime;

import java.util.List;

public record LlmMtpCalibrationResult(
        boolean supported,
        int mtpLayerCount,
        int maxDraftMaxTested,
        int bestDraftMax,
        String message,
        List<LlmMtpTrialSnapshot> trials
) {
    public LlmMtpCalibrationResult {
        mtpLayerCount = Math.max(0, mtpLayerCount);
        maxDraftMaxTested = Math.max(0, maxDraftMaxTested);
        bestDraftMax = Math.max(0, bestDraftMax);
        message = message == null ? "" : message.trim();
        trials = trials == null ? List.of() : List.copyOf(trials);
    }

    public static LlmMtpCalibrationResult unsupported() {
        return new LlmMtpCalibrationResult(false, 0, 0, 0, "", List.of());
    }
}
