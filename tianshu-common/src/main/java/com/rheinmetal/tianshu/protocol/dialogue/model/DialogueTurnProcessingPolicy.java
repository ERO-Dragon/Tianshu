package com.rheinmetal.tianshu.protocol.dialogue.model;

public record DialogueTurnProcessingPolicy(long defaultProcessingMillis, long maxProcessingMillis, boolean extendable) {
    public static final DialogueTurnProcessingPolicy DEFAULT = new DialogueTurnProcessingPolicy(30_000L, 120_000L, true);

    public DialogueTurnProcessingPolicy {
        defaultProcessingMillis = Math.max(1L, defaultProcessingMillis);
        maxProcessingMillis = Math.max(defaultProcessingMillis, maxProcessingMillis);
    }

    public long processingDeadlineAt(long nowMillis) {
        return nowMillis + defaultProcessingMillis;
    }

    public long extendDeadlineAt(long nowMillis, long requestedMillis) {
        long duration = requestedMillis <= 0L ? defaultProcessingMillis : Math.min(requestedMillis, maxProcessingMillis);
        return nowMillis + Math.max(1L, duration);
    }
}
