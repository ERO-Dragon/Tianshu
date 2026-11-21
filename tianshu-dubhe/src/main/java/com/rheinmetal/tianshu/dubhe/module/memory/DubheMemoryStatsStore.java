package com.rheinmetal.tianshu.dubhe.module.memory;

import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheStorageLayout;

public final class DubheMemoryStatsStore {
    private final DubheStorageLayout layout;
    private final DubheJsonStore jsonStore;

    public DubheMemoryStatsStore(DubheStorageLayout layout, DubheJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public DubheMemoryStatsSnapshot load(DubheScope scope) {
        if (!usable(scope)) {
            return DubheMemoryStatsSnapshot.empty("unknown_world");
        }
        return jsonStore.readObject(layout.memoryStatsFile(scope))
                .map(DubheMemoryStatsSnapshot::fromJson)
                .orElseGet(() -> DubheMemoryStatsSnapshot.empty(scope.worldId()));
    }

    public void write(DubheScope scope, DubheMemoryStatsSnapshot snapshot) {
        if (!usable(scope) || snapshot == null) {
            return;
        }
        jsonStore.writeObject(layout.memoryStatsFile(scope), snapshot.toJson());
    }

    private boolean usable(DubheScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
