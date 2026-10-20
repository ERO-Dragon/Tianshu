package com.rheinmetal.tianshu.function.tts.playback;

public record TtsPlaybackMetrics(
        String requestId,
        long firstChunkAtMillis,
        long startedAtMillis,
        int chunkCount,
        long generatedPcmMillis,
        long lowestQueueMillis,
        long backpressureMillis,
        int underrunCount
) {
    public TtsPlaybackMetrics {
        requestId = requestId == null ? "" : requestId.trim();
        chunkCount = Math.max(0, chunkCount);
        generatedPcmMillis = Math.max(0L, generatedPcmMillis);
        lowestQueueMillis = Math.max(0L, lowestQueueMillis);
        backpressureMillis = Math.max(0L, backpressureMillis);
        underrunCount = Math.max(0, underrunCount);
    }
}
