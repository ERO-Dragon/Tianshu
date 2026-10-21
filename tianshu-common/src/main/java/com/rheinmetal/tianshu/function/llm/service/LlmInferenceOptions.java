package com.rheinmetal.tianshu.function.llm.service;

public record LlmInferenceOptions(
        boolean mtpEnabled,
        Integer mtpDraftMax,
        Float vulkanPriority,
        boolean captureThinkingContent,
        String toolsJson
) {
    public LlmInferenceOptions {
        mtpDraftMax = mtpDraftMax != null && mtpDraftMax > 0 ? mtpDraftMax : null;
        vulkanPriority = normalizePriority(vulkanPriority);
        toolsJson = toolsJson == null || toolsJson.isBlank() ? null : toolsJson.trim();
    }

    public static LlmInferenceOptions defaults() {
        return new LlmInferenceOptions(false, null, null, false, null);
    }

    private static Float normalizePriority(Float value) {
        if (value == null || Float.isNaN(value)) {
            return null;
        }
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
