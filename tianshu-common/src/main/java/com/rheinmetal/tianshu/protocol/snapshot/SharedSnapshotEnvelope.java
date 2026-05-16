package com.rheinmetal.tianshu.protocol.snapshot;

public record SharedSnapshotEnvelope(
        SharedSnapshotType type,
        long version,
        long capturedAtMillis,
        long ttlMillis,
        String contentType,
        String json
) {
    public SharedSnapshotEnvelope {
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }
        version = Math.max(0L, version);
        if (capturedAtMillis <= 0L) capturedAtMillis = System.currentTimeMillis();
        ttlMillis = Math.max(0L, ttlMillis);
        contentType = contentType == null || contentType.isBlank() ? "application/json" : contentType.trim();
        json = json == null ? "" : json.trim();
    }

    public boolean expired(long nowMillis) {
        return ttlMillis > 0L && nowMillis > capturedAtMillis + ttlMillis;
    }
}
