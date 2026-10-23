package com.rheinmetal.tianshu.function.weaving.service;

/**
 * Per-request switches that change how the answer is produced, not which model produces it.
 *
 * <p>Both fields have exactly one consumer, the HTTP client. The retired local chat lane also carried a
 * Vulkan priority and an MTP draft budget, and the build kept computing them for a client that was no
 * longer instantiated — a live computation wired to a dead outlet. Only what reaches a real
 * implementation lives here now.</p>
 *
 * @param captureThinkingContent keep the provider's reasoning text, when it sends one
 * @param toolsJson              tool definitions to forward, or {@code null} for a plain chat turn
 */
public record LlmInferenceOptions(boolean captureThinkingContent, String toolsJson) {

    public LlmInferenceOptions {
        toolsJson = toolsJson == null || toolsJson.isBlank() ? null : toolsJson.trim();
    }

    public static LlmInferenceOptions defaults() {
        return new LlmInferenceOptions(false, null);
    }
}
