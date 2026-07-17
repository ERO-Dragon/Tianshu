package com.rheinmetal.tianshu.function.auxilium.module.memory.event;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStoreWriteResult;

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

    public AXStoreWriteResult append(AXScope scope, AXMemoryEvent event) {
        if (!usable(scope) || event == null || event.isEmpty()) {
            return AXStoreWriteResult.failed();
        }
        return appendAll(scope, List.of(event));
    }

    public AXStoreWriteResult appendAll(AXScope scope, List<AXMemoryEvent> events) {
        if (!usable(scope) || events == null || events.isEmpty()) {
            return AXStoreWriteResult.failed();
        }
        Set<String> existing = loadAll(scope).stream()
                .map(event -> event.stmId() + "\n" + event.factHash())
                .collect(Collectors.toSet());
        List<AXMemoryEvent> normalized = events.stream()
                .filter(event -> event != null && !event.isEmpty())
                .filter(event -> existing.add(event.stmId() + "\n" + event.factHash()))
                .toList();
        if (normalized.isEmpty()) {
            return AXStoreWriteResult.success(0);
        }
        boolean written = jsonStore.tryAppendJsonLines(layout.eventsFile(scope), normalized.stream().map(AXMemoryEvent::toJson).toList());
        return written ? AXStoreWriteResult.success(normalized.size()) : AXStoreWriteResult.failed();
    }

    private boolean usable(AXScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
