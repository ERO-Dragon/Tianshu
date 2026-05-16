package com.rheinmetal.tianshu.protocol.payload;

public record LlmUsageAuthorizationPayload(
        String sessionId,
        String turnId
) {
    public static final LlmUsageAuthorizationPayload EMPTY = new LlmUsageAuthorizationPayload("", "");

    public LlmUsageAuthorizationPayload {
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
