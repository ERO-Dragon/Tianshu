package com.rheinmetal.tianshu.function.llm.service;

public record LlmInferencePolicy(
        Boolean frameGuardEnabled,
        Integer targetFps,
        Boolean mtpEnabled
) {
    public LlmInferencePolicy {
        targetFps = normalizeTargetFps(targetFps);
    }

    public static LlmInferencePolicy defaults() {
        return new LlmInferencePolicy(null, null, null);
    }

    private static Integer normalizeTargetFps(Integer value) {
        if (value == null) {
            return null;
        }
        return Math.max(15, Math.min(240, value));
    }
}
