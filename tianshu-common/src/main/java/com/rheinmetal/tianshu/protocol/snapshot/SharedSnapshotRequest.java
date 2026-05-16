package com.rheinmetal.tianshu.protocol.snapshot;

public record SharedSnapshotRequest(
        SharedSnapshotType type,
        String requesterModuleId,
        boolean allowStale,
        long maxAgeMillis,
        SharedSnapshotFallbackPolicy fallbackPolicy
) {
    public SharedSnapshotRequest(SharedSnapshotType type, String requesterModuleId, boolean allowStale, long maxAgeMillis) {
        this(type, requesterModuleId, allowStale, maxAgeMillis, SharedSnapshotFallbackPolicy.OPTIONAL);
    }

    public SharedSnapshotRequest {
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }
        requesterModuleId = requesterModuleId == null ? "" : requesterModuleId.trim();
        maxAgeMillis = Math.max(0L, maxAgeMillis);
        fallbackPolicy = fallbackPolicy == null ? SharedSnapshotFallbackPolicy.OPTIONAL : fallbackPolicy;
    }

    public boolean callerMustHandleFallback() {
        return fallbackPolicy != SharedSnapshotFallbackPolicy.REQUIRE_SHARED;
    }
}
