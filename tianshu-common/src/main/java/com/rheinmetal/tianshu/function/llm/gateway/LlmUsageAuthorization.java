package com.rheinmetal.tianshu.function.llm.gateway;

public record LlmUsageAuthorization(
        String sessionId,
        String turnId
) {
    public static final LlmUsageAuthorization EMPTY = new LlmUsageAuthorization("", "");

    public LlmUsageAuthorization {
        sessionId = normalizeOptional(sessionId);
        turnId = normalizeOptional(turnId);
    }

    public boolean isPresent() {
        return !sessionId.isBlank();
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }
}
