package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.index;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.util.Optional;

public final class AXMemoryRetrievalIndexSnapshotStore {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;

    public AXMemoryRetrievalIndexSnapshotStore(AXStorageLayout layout, AXJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public Optional<AXMemoryRetrievalIndexSnapshot> load(AXScope scope, String embeddingNamespace) {
        if (!usable(scope, embeddingNamespace)) {
            return Optional.empty();
        }
        return jsonStore.readObject(layout.retrievalIndexSnapshotFile(scope, embeddingNamespace))
                .map(AXMemoryRetrievalIndexSnapshot::fromJson)
                .filter(snapshot -> snapshot.schemaVersion() == AXMemoryRetrievalIndexSnapshot.SCHEMA_VERSION);
    }

    public void write(AXScope scope, AXMemoryRetrievalIndexSnapshot snapshot) {
        if (snapshot == null || !usable(scope, snapshot.embeddingNamespace())) {
            return;
        }
        jsonStore.writeObject(layout.retrievalIndexSnapshotFile(scope, snapshot.embeddingNamespace()), snapshot.toJson());
    }

    private boolean usable(AXScope scope, String embeddingNamespace) {
        return scope != null
                && scope.writable()
                && embeddingNamespace != null
                && !embeddingNamespace.isBlank()
                && layout != null
                && jsonStore != null;
    }
}
