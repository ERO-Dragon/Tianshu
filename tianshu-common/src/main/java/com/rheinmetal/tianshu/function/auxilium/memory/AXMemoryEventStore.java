package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class AXMemoryEventStore {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;

    public AXMemoryEventStore(AXStorageLayout layout, AXJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public List<AXMemoryEvent> loadAll(AXScope scope) {
        if (!usable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(layout.eventsFile(scope)).stream()
                .map(AXMemoryEvent::fromJson)
                .filter(event -> !event.isEmpty())
                .toList();
    }

    public void append(AXScope scope, AXMemoryEvent event) {
        if (!usable(scope) || event == null || event.isEmpty()) {
            return;
        }
        appendAll(scope, List.of(event));
    }

    public void appendAll(AXScope scope, List<AXMemoryEvent> events) {
        if (!usable(scope) || events == null || events.isEmpty()) {
            return;
        }
        Set<String> existing = loadAll(scope).stream()
                .map(event -> event.stmId() + "\n" + event.factHash())
                .collect(Collectors.toSet());
        List<AXMemoryEvent> normalized = events.stream()
                .filter(event -> event != null && !event.isEmpty())
                .filter(event -> existing.add(event.stmId() + "\n" + event.factHash()))
                .toList();
        jsonStore.appendJsonLines(layout.eventsFile(scope), normalized.stream().map(AXMemoryEvent::toJson).toList());
    }

    private boolean usable(AXScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
