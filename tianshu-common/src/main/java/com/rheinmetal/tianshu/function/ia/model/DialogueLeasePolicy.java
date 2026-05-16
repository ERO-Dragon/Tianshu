package com.rheinmetal.tianshu.function.ia.model;

public record DialogueLeasePolicy(long defaultLeaseMillis, long maxLeaseMillis, boolean renewable) {
    public static final DialogueLeasePolicy DEFAULT = new DialogueLeasePolicy(30_000L, 120_000L, true);

    public DialogueLeasePolicy {
        defaultLeaseMillis = Math.max(1L, defaultLeaseMillis);
        maxLeaseMillis = Math.max(defaultLeaseMillis, maxLeaseMillis);
    }

    public long leaseExpireAt(long nowMillis) {
        return nowMillis + defaultLeaseMillis;
    }

    public long renewExpireAt(long nowMillis, long requestedMillis) {
        long duration = requestedMillis <= 0L ? defaultLeaseMillis : Math.min(requestedMillis, maxLeaseMillis);
        return nowMillis + Math.max(1L, duration);
    }
}
