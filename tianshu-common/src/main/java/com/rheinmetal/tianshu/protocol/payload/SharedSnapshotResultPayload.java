package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.snapshot.SharedSnapshotEnvelope;
import com.rheinmetal.tianshu.protocol.snapshot.SharedSnapshotRequest;

import java.util.Optional;

public record SharedSnapshotResultPayload(SharedSnapshotRequest request, SharedSnapshotEnvelope snapshot, String status, long timestampMillis) implements ITianshuPayload {
    public SharedSnapshotResultPayload {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        status = status == null || status.isBlank() ? (snapshot == null ? "missing" : "ok") : status.trim();
        if (timestampMillis <= 0L) timestampMillis = System.currentTimeMillis();
    }

    public Optional<SharedSnapshotEnvelope> optionalSnapshot() {
        return Optional.ofNullable(snapshot);
    }
}
