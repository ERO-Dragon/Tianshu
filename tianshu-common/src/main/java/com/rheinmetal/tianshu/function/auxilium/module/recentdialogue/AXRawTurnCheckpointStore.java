package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.util.List;

public final class AXRawTurnCheckpointStore {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;

    public AXRawTurnCheckpointStore(AXStorageLayout layout, AXJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public List<AXRawTurn> load(AXScope scope) {
        if (!usable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(layout.rawTurnCheckpointFile(scope)).stream()
                .map(AXRawTurn::fromJson)
                .filter(turn -> !turn.isEmpty())
                .toList();
    }

    public void write(AXScope scope, List<AXRawTurn> turns) {
        if (!usable(scope)) {
            return;
        }
        List<AXRawTurn> normalized = turns == null ? List.of() : turns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .toList();
        jsonStore.writeJsonLines(
                layout.rawTurnCheckpointFile(scope),
                normalized.stream().map(AXRawTurn::toJson).toList()
        );
    }

    private boolean usable(AXScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
