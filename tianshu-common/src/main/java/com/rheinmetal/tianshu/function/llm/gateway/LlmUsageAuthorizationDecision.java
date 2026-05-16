package com.rheinmetal.tianshu.function.llm.gateway;

public record LlmUsageAuthorizationDecision(
        boolean allowed,
        String reasonCode,
        String message
) {
    public static LlmUsageAuthorizationDecision allow() {
        return new LlmUsageAuthorizationDecision(true, "", "");
    }

    public static LlmUsageAuthorizationDecision denied(String reasonCode, String message) {
        return new LlmUsageAuthorizationDecision(false, normalize(reasonCode, "LLM_USAGE_AUTH_DENIED"), normalize(message, "LLM usage authorization denied"));
    }

    public static LlmUsageAuthorizationDecision unavailable(String message) {
        return new LlmUsageAuthorizationDecision(false, "LLM_USAGE_AUTH_UNAVAILABLE", normalize(message, "LLM usage authorization is unavailable"));
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
