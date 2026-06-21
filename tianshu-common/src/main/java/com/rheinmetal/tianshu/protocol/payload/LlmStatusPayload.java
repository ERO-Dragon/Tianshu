package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.Locale;

public record LlmStatusPayload(
        String taskId,
        String taskType,
        String lane,
        String eventType,
        int priority,
        String message,
        int replayCharacters,
        int generatedTokens,
        String errorMessage,
        long occurredAtMillis
) implements ITianshuPayload {
    public static final String QUEUED = "QUEUED";
    public static final String STARTED = "STARTED";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String PREFILL_STARTED = "PREFILL_STARTED";
    public static final String PREFILL_COMPLETED = "PREFILL_COMPLETED";
    public static final String GENERATION_STARTED = "GENERATION_STARTED";
    public static final String COLD_RESUME_STARTED = "COLD_RESUME_STARTED";
    public static final String COLD_RESUME_COMPLETED = "COLD_RESUME_COMPLETED";

    public LlmStatusPayload {
        taskId = taskId == null ? "" : taskId.trim();
        taskType = taskType == null ? "" : taskType.trim().toUpperCase(Locale.ROOT);
        lane = lane == null || lane.isBlank() ? "CHAT" : lane.trim().toUpperCase(Locale.ROOT);
        eventType = normalizeEventType(eventType);
        priority = Math.max(0, priority);
        message = message == null ? "" : message.trim();
        replayCharacters = Math.max(0, replayCharacters);
        generatedTokens = Math.max(0, generatedTokens);
        errorMessage = errorMessage == null ? "" : errorMessage.trim();
        occurredAtMillis = occurredAtMillis > 0L ? occurredAtMillis : System.currentTimeMillis();
    }

    private static String normalizeEventType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case QUEUED,
                    STARTED,
                    COMPLETED,
                    CANCELLED,
                    FAILED,
                    UNKNOWN,
                    SUSPENDED,
                    PREFILL_STARTED,
                    PREFILL_COMPLETED,
                    GENERATION_STARTED,
                    COLD_RESUME_STARTED,
                    COLD_RESUME_COMPLETED -> normalized;
            default -> UNKNOWN;
        };
    }
}
