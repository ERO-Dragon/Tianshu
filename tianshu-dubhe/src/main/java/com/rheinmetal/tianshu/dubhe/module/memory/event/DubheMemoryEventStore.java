package com.rheinmetal.tianshu.dubhe.module.memory.event;

import com.rheinmetal.tianshu.dubhe.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.storage.DubheStorageLayout;
import com.rheinmetal.tianshu.dubhe.storage.DubheStoreWriteResult;

import java.util.List;

/**
 * Append-only store of memory events.
 *
 * <p>An event is an objective record of something that happened, so this store never removes events
 * that look like older ones. Two identical facts written at two different moments are two events, not
 * one; collapsing them here used to hide facts and cost a full read of the event log on every append.
 * The only identity this store preserves is the one the event itself carries ({@link DubheMemoryEvent#id()}),
 * and keeping the vector index free of repeated entries is the index layer's job
 * ({@code DubheEventVectorStore}), not this store's.</p>
 */
public final class DubheMemoryEventStore {
    private final DubheStorageLayout layout;
    private final DubheJsonStore jsonStore;

    public DubheMemoryEventStore(DubheStorageLayout layout, DubheJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public List<DubheMemoryEvent> loadAll(DubheScope scope) {
        if (!usable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(layout.eventsFile(scope)).stream()
                .map(DubheMemoryEvent::fromJson)
                .filter(event -> !event.isEmpty())
                .toList();
    }

    public DubheStoreWriteResult append(DubheScope scope, DubheMemoryEvent event) {
        if (!usable(scope) || event == null || event.isEmpty()) {
            return DubheStoreWriteResult.failed();
        }
        return appendAll(scope, List.of(event));
    }

    public DubheStoreWriteResult appendAll(DubheScope scope, List<DubheMemoryEvent> events) {
        if (!usable(scope) || events == null || events.isEmpty()) {
            return DubheStoreWriteResult.failed();
        }
        List<DubheMemoryEvent> normalized = events.stream()
                .filter(event -> event != null && !event.isEmpty())
                .toList();
        if (normalized.isEmpty()) {
            return DubheStoreWriteResult.success(0);
        }
        boolean written = jsonStore.tryAppendJsonLines(layout.eventsFile(scope), normalized.stream().map(DubheMemoryEvent::toJson).toList());
        return written ? DubheStoreWriteResult.success(normalized.size()) : DubheStoreWriteResult.failed();
    }

    private boolean usable(DubheScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
