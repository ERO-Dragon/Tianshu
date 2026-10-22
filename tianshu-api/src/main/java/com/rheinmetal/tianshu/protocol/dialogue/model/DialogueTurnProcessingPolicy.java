package com.rheinmetal.tianshu.protocol.dialogue.model;

public record DialogueTurnProcessingPolicy(long defaultProcessingMillis, long maxProcessingMillis, boolean extendable) {
    public static final DialogueTurnProcessingPolicy DEFAULT = new DialogueTurnProcessingPolicy(10_000L, 180_000L, true);

    public DialogueTurnProcessingPolicy {
        defaultProcessingMillis = Math.max(1L, defaultProcessingMillis);
        maxProcessingMillis = Math.max(defaultProcessingMillis, maxProcessingMillis);
    }

    public long processingDeadlineAt(long nowMillis) {
        return safeAdd(Math.max(0L, nowMillis), defaultProcessingMillis);
    }

    public long absoluteDeadlineAt(long createdAtMillis) {
        return safeAdd(Math.max(0L, createdAtMillis), maxProcessingMillis);
    }

    public long extendDeadlineAt(long createdAtMillis, long nowMillis, long requestedMillis) {
        long duration = requestedMillis <= 0L ? defaultProcessingMillis : Math.min(requestedMillis, maxProcessingMillis);
        long effectiveNow = Math.max(Math.max(0L, createdAtMillis), Math.max(0L, nowMillis));
        return Math.min(safeAdd(effectiveNow, Math.max(1L, duration)), absoluteDeadlineAt(createdAtMillis));
    }

    private static long safeAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
