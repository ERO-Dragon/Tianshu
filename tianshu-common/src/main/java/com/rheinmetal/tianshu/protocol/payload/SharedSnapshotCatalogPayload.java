package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.snapshot.SharedSnapshotDescriptor;

import java.util.List;

public record SharedSnapshotCatalogPayload(List<SharedSnapshotDescriptor> descriptors, long timestampMillis) implements ITianshuPayload {
    public SharedSnapshotCatalogPayload {
        descriptors = descriptors == null || descriptors.isEmpty() ? List.of() : List.copyOf(descriptors);
        if (timestampMillis <= 0L) timestampMillis = System.currentTimeMillis();
    }
}
