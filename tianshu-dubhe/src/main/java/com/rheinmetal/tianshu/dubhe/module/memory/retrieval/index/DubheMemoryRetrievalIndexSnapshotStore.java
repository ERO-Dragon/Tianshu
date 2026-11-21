package com.rheinmetal.tianshu.dubhe.module.memory.retrieval.index;

import com.rheinmetal.tianshu.dubhe.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.storage.DubheStorageLayout;

import java.util.Optional;

public final class DubheMemoryRetrievalIndexSnapshotStore {
    private final DubheStorageLayout layout;
    private final DubheJsonStore jsonStore;

    public DubheMemoryRetrievalIndexSnapshotStore(DubheStorageLayout layout, DubheJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public Optional<DubheMemoryRetrievalIndexSnapshot> load(DubheScope scope, String embeddingNamespace) {
        if (!usable(scope, embeddingNamespace)) {
            return Optional.empty();
        }
        return jsonStore.readObject(layout.retrievalIndexSnapshotFile(scope, embeddingNamespace))
                .map(DubheMemoryRetrievalIndexSnapshot::fromJson)
                .filter(snapshot -> snapshot.schemaVersion() == DubheMemoryRetrievalIndexSnapshot.SCHEMA_VERSION);
    }

    public void write(DubheScope scope, DubheMemoryRetrievalIndexSnapshot snapshot) {
        if (snapshot == null || !usable(scope, snapshot.embeddingNamespace())) {
            return;
        }
        jsonStore.writeObject(layout.retrievalIndexSnapshotFile(scope, snapshot.embeddingNamespace()), snapshot.toJson());
    }

    private boolean usable(DubheScope scope, String embeddingNamespace) {
        return scope != null
                && scope.writable()
                && embeddingNamespace != null
                && !embeddingNamespace.isBlank()
                && layout != null
                && jsonStore != null;
    }
}
