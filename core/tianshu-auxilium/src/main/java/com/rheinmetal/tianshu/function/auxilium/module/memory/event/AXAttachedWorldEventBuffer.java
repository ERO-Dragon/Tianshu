package com.rheinmetal.tianshu.function.auxilium.module.memory.event;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AXAttachedWorldEventBuffer {
    private static final int MAX_EVENTS_PER_WORLD = 512;
    private static final long RETENTION_MILLIS = 30L * 60L * 1000L;

    private final Map<String, Deque<AXAttachedWorldEvent>> eventsByWorld = new ConcurrentHashMap<>();

    public void append(AXScope scope, AXAttachedWorldEvent event) {
        appendAll(scope, List.of(event));
    }

    public void appendAll(AXScope scope, List<AXAttachedWorldEvent> events) {
        if (!usable(scope) || events == null || events.isEmpty()) {
            return;
        }
        Deque<AXAttachedWorldEvent> buffer = eventsByWorld.computeIfAbsent(scope.worldId(), ignored -> new ArrayDeque<>());
        synchronized (buffer) {
            prune(buffer, System.currentTimeMillis());
            Set<String> existing = new HashSet<>();
            for (AXAttachedWorldEvent current : buffer) {
                existing.add(current.dedupKey());
            }
            for (AXAttachedWorldEvent event : events) {
                if (event != null && !event.isEmpty() && existing.add(event.dedupKey())) {
                    buffer.addLast(event);
                }
            }
            trimSize(buffer);
        }
    }

    public List<AXAttachedWorldEvent> loadAll(AXScope scope) {
        if (!usable(scope)) {
            return List.of();
        }
        Deque<AXAttachedWorldEvent> buffer = eventsByWorld.get(scope.worldId());
        if (buffer == null) {
            return List.of();
        }
        synchronized (buffer) {
            prune(buffer, System.currentTimeMillis());
            return List.copyOf(buffer);
        }
    }

    public List<AXAttachedWorldEvent> loadInRange(AXScope scope, long fromMillis, long toMillis, Set<String> excludedIds) {
        if (!usable(scope) || fromMillis <= 0L || toMillis < fromMillis) {
            return List.of();
        }
        Set<String> excluded = excludedIds == null ? Set.of() : excludedIds;
        List<AXAttachedWorldEvent> result = new ArrayList<>();
        for (AXAttachedWorldEvent event : loadAll(scope)) {
            if (event.happenedAtMillis() >= fromMillis
                    && event.happenedAtMillis() <= toMillis
                    && !excluded.contains(event.id())) {
                result.add(event);
            }
        }
        return List.copyOf(result);
    }

    private void prune(Deque<AXAttachedWorldEvent> buffer, long nowMillis) {
        long cutoff = Math.max(0L, nowMillis - RETENTION_MILLIS);
        while (!buffer.isEmpty()) {
            AXAttachedWorldEvent first = buffer.peekFirst();
            if (first == null || first.happenedAtMillis() < cutoff) {
                buffer.removeFirst();
                continue;
            }
            break;
        }
        trimSize(buffer);
    }

    private void trimSize(Deque<AXAttachedWorldEvent> buffer) {
        while (buffer.size() > MAX_EVENTS_PER_WORLD) {
            buffer.removeFirst();
        }
    }

    private boolean usable(AXScope scope) {
        return scope != null && scope.writable();
    }
}
