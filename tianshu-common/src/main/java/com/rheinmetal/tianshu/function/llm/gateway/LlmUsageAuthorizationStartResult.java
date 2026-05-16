package com.rheinmetal.tianshu.function.llm.gateway;

public record LlmUsageAuthorizationStartResult(
        boolean started,
        String code,
        String message
) {
    public static LlmUsageAuthorizationStartResult startedResult() {
        return new LlmUsageAuthorizationStartResult(true, "", "");
    }

    public static LlmUsageAuthorizationStartResult rejected(String code, String message) {
        return new LlmUsageAuthorizationStartResult(false, normalize(code, "LLM_USAGE_AUTH_UNAVAILABLE"), normalize(message, "LLM usage authorization is unavailable"));
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
