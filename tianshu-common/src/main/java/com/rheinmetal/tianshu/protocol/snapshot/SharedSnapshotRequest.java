package com.rheinmetal.tianshu.protocol.snapshot;

public record SharedSnapshotRequest(
        SharedSnapshotType type,
        String requesterModuleId,
        boolean allowStale,
        long maxAgeMillis
) {
    public SharedSnapshotRequest {
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }
        requesterModuleId = requesterModuleId == null ? "" : requesterModuleId.trim();
        maxAgeMillis = Math.max(0L, maxAgeMillis);
    }
}
