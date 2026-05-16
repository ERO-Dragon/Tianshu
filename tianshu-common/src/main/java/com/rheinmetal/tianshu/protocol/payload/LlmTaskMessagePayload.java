package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record LlmTaskMessagePayload(String role, String content) implements ITianshuPayload {
    public LlmTaskMessagePayload {
        role = normalizeRole(role);
        content = content == null ? "" : content;
    }

    private static String normalizeRole(String value) {
        if (value == null || value.isBlank()) {
            return "user";
        }
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "system", "assistant", "user" -> normalized;
            default -> "user";
        };
    }
}
