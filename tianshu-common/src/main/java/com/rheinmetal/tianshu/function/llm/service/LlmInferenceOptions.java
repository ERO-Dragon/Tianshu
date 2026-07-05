package com.rheinmetal.tianshu.function.llm.service;

public record LlmInferenceOptions(
        boolean mtpEnabled,
        Integer mtpDraftMax,
        Float vulkanPriority,
        boolean captureThinkingContent,
        String toolsJson
) {
    public LlmInferenceOptions(boolean mtpEnabled, Integer mtpDraftMax, Float vulkanPriority) {
        this(mtpEnabled, mtpDraftMax, vulkanPriority, false, null);
    }

    public LlmInferenceOptions {
        mtpDraftMax = mtpDraftMax != null && mtpDraftMax > 0 ? mtpDraftMax : null;
        vulkanPriority = normalizePriority(vulkanPriority);
        toolsJson = toolsJson == null || toolsJson.isBlank() ? null : toolsJson.trim();
    }

    public static LlmInferenceOptions defaults() {
        return new LlmInferenceOptions(false, null, null, false, null);
    }

    public boolean hasExecutionOptions() {
        return mtpEnabled || mtpDraftMax != null || vulkanPriority != null
                || captureThinkingContent || toolsJson != null;
    }

    public LlmInferenceOptions withRequestOptions(boolean captureThinkingContent, String toolsJson) {
        return new LlmInferenceOptions(mtpEnabled, mtpDraftMax, vulkanPriority, captureThinkingContent, toolsJson);
    }

    private static Float normalizePriority(Float value) {
        if (value == null || Float.isNaN(value)) {
            return null;
        }
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
