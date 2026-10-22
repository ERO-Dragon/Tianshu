package com.rheinmetal.tianshu.protocol.observability;

import java.util.List;

public record ProtocolExecutorSnapshot(
    List<ProtocolLaneSnapshot> lanes,
    int runningTasks,
    int queuedTasks
) {
    public ProtocolExecutorSnapshot {
        lanes = lanes == null ? List.of() : List.copyOf(lanes);
    }
}
