package com.rheinmetal.tianshu.function.llm.service;

public record LlmInferenceOptions(
        boolean mtpEnabled,
        Integer mtpDraftMax,
        Float vulkanPriority
) {
    public LlmInferenceOptions {
        mtpDraftMax = mtpDraftMax != null && mtpDraftMax > 0 ? mtpDraftMax : null;
        vulkanPriority = normalizePriority(vulkanPriority);
    }

    public static LlmInferenceOptions defaults() {
        return new LlmInferenceOptions(false, null, null);
    }

    public boolean hasExecutionOptions() {
        return mtpEnabled || mtpDraftMax != null || vulkanPriority != null;
    }

    private static Float normalizePriority(Float value) {
        if (value == null || Float.isNaN(value)) {
            return null;
        }
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
