package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record TtsRequestStatusPayload(
        String requestId,
        String sourceId,
        long sessionId,
        int turnId,
        TtsRequestStatus status,
        String failureCode,
        long occurredAtMillis
) implements ITianshuPayload {
    public TtsRequestStatusPayload {
        requestId = normalize(requestId);
        sourceId = normalize(sourceId);
        status = status == null ? TtsRequestStatus.QUEUED : status;
        failureCode = normalize(failureCode);
        occurredAtMillis = occurredAtMillis > 0L ? occurredAtMillis : System.currentTimeMillis();
    }

    public static TtsRequestStatusPayload now(
            String requestId,
            String sourceId,
            long sessionId,
            int turnId,
            TtsRequestStatus status,
            String failureCode
    ) {
        return new TtsRequestStatusPayload(
                requestId,
                sourceId,
                sessionId,
                turnId,
                status,
                failureCode,
                System.currentTimeMillis()
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
