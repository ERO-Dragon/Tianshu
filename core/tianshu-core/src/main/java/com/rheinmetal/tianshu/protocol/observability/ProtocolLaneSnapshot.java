package com.rheinmetal.tianshu.protocol.observability;

import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;

public record ProtocolLaneSnapshot(
    ExecutionLane lane,
    int poolSize,
    int activeCount,
    int queuedCount,
    long completedCount,
    long rejectedCount
) {
}
