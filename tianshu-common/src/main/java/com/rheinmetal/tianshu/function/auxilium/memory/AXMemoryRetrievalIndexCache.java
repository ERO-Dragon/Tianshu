package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

final class AXMemoryRetrievalIndexCache {
    private final ConcurrentMap<String, CachedIndex> indexes = new ConcurrentHashMap<>();

    AXMemoryRetrievalIndex get(AXScope scope, String embeddingNamespace, SourceStamp sourceStamp, Supplier<AXMemoryRetrievalIndex> builder) {
        if (scope == null || !scope.writable() || embeddingNamespace == null || embeddingNamespace.isBlank() || builder == null) {
            return AXMemoryRetrievalIndex.empty(embeddingNamespace);
        }
        String key = key(scope, embeddingNamespace);
        CachedIndex cached = indexes.get(key);
        if (cached != null && Objects.equals(cached.sourceStamp(), sourceStamp)) {
            return cached.index();
        }
        AXMemoryRetrievalIndex built = builder.get();
        indexes.put(key, new CachedIndex(sourceStamp, built == null ? AXMemoryRetrievalIndex.empty(embeddingNamespace) : built));
        return indexes.get(key).index();
    }

    void invalidate(AXScope scope) {
        if (scope == null) {
            indexes.clear();
            return;
        }
        String prefix = scope.worldId() + "\n";
        indexes.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private String key(AXScope scope, String embeddingNamespace) {
        return scope.worldId() + "\n" + embeddingNamespace;
    }

    record SourceStamp(long eventsSize, long eventsModifiedAtMillis, long vectorsSize, long vectorsModifiedAtMillis) {
    }

    private record CachedIndex(SourceStamp sourceStamp, AXMemoryRetrievalIndex index) {
    }
}
