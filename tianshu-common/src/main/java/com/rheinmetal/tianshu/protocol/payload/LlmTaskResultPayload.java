package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record LlmTaskResultPayload(
        String taskId,
        String purpose,
        String status,
        String text,
        String errorCode,
        String errorMessage
) implements ITianshuPayload {
    public LlmTaskResultPayload {
        taskId = normalize(taskId, "llm.task");
        purpose = normalize(purpose, "llm.task");
        status = normalize(status, "FAILED");
        text = text == null ? "" : text;
        errorCode = normalizeOptional(errorCode);
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
