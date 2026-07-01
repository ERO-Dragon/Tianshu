package com.rheinmetal.tianshu.function.auxilium.module.memory.event;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class AXAttachedWorldEventStore {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;

    public AXAttachedWorldEventStore(AXStorageLayout layout, AXJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public List<AXAttachedWorldEvent> loadAll(AXScope scope) {
        if (!usable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(layout.attachedWorldEventsFile(scope)).stream()
                .map(AXAttachedWorldEvent::fromJson)
                .filter(event -> !event.isEmpty())
                .toList();
    }

    public void appendAll(AXScope scope, List<AXAttachedWorldEvent> events) {
        if (!usable(scope) || events == null || events.isEmpty()) {
            return;
        }
        Set<String> existing = loadAll(scope).stream()
                .map(AXAttachedWorldEvent::dedupKey)
                .collect(Collectors.toSet());
        List<AXAttachedWorldEvent> normalized = events.stream()
                .filter(event -> event != null && !event.isEmpty())
                .filter(event -> existing.add(event.dedupKey()))
                .toList();
        jsonStore.appendJsonLines(layout.attachedWorldEventsFile(scope), normalized.stream().map(AXAttachedWorldEvent::toJson).toList());
    }

    private boolean usable(AXScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
