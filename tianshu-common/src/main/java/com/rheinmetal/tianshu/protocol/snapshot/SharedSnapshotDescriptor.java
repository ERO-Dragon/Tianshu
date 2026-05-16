package com.rheinmetal.tianshu.protocol.snapshot;

public record SharedSnapshotDescriptor(
        SharedSnapshotType type,
        String topic,
        SharedSnapshotCost cost,
        long version,
        long updatedAtMillis,
        long ttlMillis
) {
    public SharedSnapshotDescriptor {
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }
        topic = topic == null ? "" : topic.trim();
        cost = cost == null ? SharedSnapshotCost.LOW : cost;
        version = Math.max(0L, version);
        if (updatedAtMillis <= 0L) updatedAtMillis = System.currentTimeMillis();
        ttlMillis = Math.max(0L, ttlMillis);
    }

    public boolean expired(long nowMillis) {
        return ttlMillis > 0L && nowMillis > updatedAtMillis + ttlMillis;
    }
}
