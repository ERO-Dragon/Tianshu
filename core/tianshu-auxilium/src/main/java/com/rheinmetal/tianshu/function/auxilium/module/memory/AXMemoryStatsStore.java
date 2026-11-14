package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

public final class AXMemoryStatsStore {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;

    public AXMemoryStatsStore(AXStorageLayout layout, AXJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public AXMemoryStatsSnapshot load(AXScope scope) {
        if (!usable(scope)) {
            return AXMemoryStatsSnapshot.empty("unknown_world");
        }
        return jsonStore.readObject(layout.memoryStatsFile(scope))
                .map(AXMemoryStatsSnapshot::fromJson)
                .orElseGet(() -> AXMemoryStatsSnapshot.empty(scope.worldId()));
    }

    public void write(AXScope scope, AXMemoryStatsSnapshot snapshot) {
        if (!usable(scope) || snapshot == null) {
            return;
        }
        jsonStore.writeObject(layout.memoryStatsFile(scope), snapshot.toJson());
    }

    private boolean usable(AXScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
