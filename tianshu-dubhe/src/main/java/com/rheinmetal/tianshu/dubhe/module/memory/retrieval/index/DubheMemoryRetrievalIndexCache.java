package com.rheinmetal.tianshu.dubhe.module.memory.retrieval.index;

import com.rheinmetal.tianshu.dubhe.scope.DubheScope;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

public final class DubheMemoryRetrievalIndexCache {
    private final ConcurrentMap<String, CachedIndex> indexes = new ConcurrentHashMap<>();

    public DubheMemoryRetrievalIndex get(DubheScope scope, String embeddingNamespace, SourceStamp sourceStamp, Supplier<DubheMemoryRetrievalIndex> builder) {
        if (scope == null || !scope.writable() || embeddingNamespace == null || embeddingNamespace.isBlank() || builder == null) {
            return DubheMemoryRetrievalIndex.empty(embeddingNamespace);
        }
        String key = key(scope, embeddingNamespace);
        CachedIndex cached = indexes.get(key);
        if (cached != null && Objects.equals(cached.sourceStamp(), sourceStamp)) {
            return cached.index();
        }
        DubheMemoryRetrievalIndex built = builder.get();
        indexes.put(key, new CachedIndex(sourceStamp, built == null ? DubheMemoryRetrievalIndex.empty(embeddingNamespace) : built));
        return indexes.get(key).index();
    }

    public void invalidate(DubheScope scope) {
        if (scope == null) {
            indexes.clear();
            return;
        }
        String prefix = scope.worldId() + "\n";
        indexes.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private String key(DubheScope scope, String embeddingNamespace) {
        return scope.worldId() + "\n" + embeddingNamespace;
    }

    public record SourceStamp(long eventsSize, long eventsModifiedAtMillis, long vectorsSize, long vectorsModifiedAtMillis) {
    }

    private record CachedIndex(SourceStamp sourceStamp, DubheMemoryRetrievalIndex index) {
    }
}
