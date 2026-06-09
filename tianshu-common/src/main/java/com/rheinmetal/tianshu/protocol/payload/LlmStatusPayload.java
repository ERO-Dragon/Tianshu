package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record LlmStatusPayload(String requestId, String traceId, String lane, String status, long occurredAtMillis) implements ITianshuPayload {
    public static final String ACCEPTED = "ACCEPTED";
    public static final String QUEUED = "QUEUED";
    public static final String STREAMING = "STREAMING";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String FAILED = "FAILED";

    public LlmStatusPayload {
        requestId = requestId == null ? "" : requestId.trim();
        traceId = traceId == null ? "" : traceId.trim();
        lane = lane == null || lane.isBlank() ? "CHAT" : lane.trim().toUpperCase();
        status = normalizeStatus(status);
        occurredAtMillis = occurredAtMillis > 0L ? occurredAtMillis : System.currentTimeMillis();
    }

    private static String normalizeStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        return switch (normalized) {
            case ACCEPTED, QUEUED, STREAMING, COMPLETED, CANCELLED, FAILED -> normalized;
            default -> FAILED;
        };
    }
}
