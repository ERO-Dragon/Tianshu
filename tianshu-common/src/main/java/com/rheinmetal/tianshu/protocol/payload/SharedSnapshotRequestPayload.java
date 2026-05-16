package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.snapshot.SharedSnapshotRequest;

public record SharedSnapshotRequestPayload(SharedSnapshotRequest request, long timestampMillis) implements ITianshuPayload {
    public SharedSnapshotRequestPayload {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        if (timestampMillis <= 0L) timestampMillis = System.currentTimeMillis();
    }
}
