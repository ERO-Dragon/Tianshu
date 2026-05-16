package com.rheinmetal.tianshu.function.assistant.memory;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;
import com.rheinmetal.tianshu.function.assistant.storage.AssistantJsonStore;
import com.rheinmetal.tianshu.function.assistant.storage.AssistantStorageLayout;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LongTermMemoryRagStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AssistantStorageLayout layout;
    private final AssistantJsonStore jsonStore;
    private final int maxEntries;
    private final long ttlMillis;

    public LongTermMemoryRagStore(AssistantStorageLayout layout, AssistantJsonStore jsonStore) {
        this(layout, jsonStore, null);
    }

    public LongTermMemoryRagStore(AssistantStorageLayout layout, AssistantJsonStore jsonStore, ITianshuConfig config) {
        this.layout = layout;
        this.jsonStore = jsonStore;
        this.maxEntries = config == null ? 2048 : Math.max(128, config.getLlmAssistantLongTermMemoryMaxEntries());
        this.ttlMillis = config == null ? 1209600000L : Math.max(0L, config.getLlmAssistantLongTermMemoryTtlMillis());
    }

    public List<LongTermMemoryEntry> load(AssistantScope scope) {
        if (!writable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(metadataFile(scope)).stream()
                .map(LongTermMemoryEntry::fromJson)
                .filter(entry -> !entry.isEmpty())
                .toList();
    }

    public boolean hasEntries(AssistantScope scope) {
        return !load(scope).isEmpty();
    }

    public LongTermMemoryEntry appendOne(AssistantScope scope, String memory) {
        if (!writable(scope) || memory == null || memory.isBlank()) {
            return new LongTermMemoryEntry("", "", 0L, 0L, 0, 0.0D);
        }
        Map<String, LongTermMemoryEntry> byText = new LinkedHashMap<>();
        for (LongTermMemoryEntry entry : load(scope)) {
            byText.put(normalize(entry.longTermMemory()), entry);
        }
        String normalized = normalize(memory);
        LongTermMemoryEntry existing = byText.get(normalized);
        if (existing != null) {
            return existing;
        }
        long now = System.currentTimeMillis();
        LongTermMemoryEntry entry = new LongTermMemoryEntry(newUid(now), normalized, now, 0L, 0, 0.5D);
        byText.put(normalized, entry);
        write(scope, new ArrayList<>(byText.values()));
        return entry;
    }

    public void append(AssistantScope scope, List<String> memories) {
        if (!writable(scope) || memories == null || memories.isEmpty()) {
            return;
        }
        Map<String, LongTermMemoryEntry> byText = new LinkedHashMap<>();
        for (LongTermMemoryEntry entry : load(scope)) {
            byText.put(normalize(entry.longTermMemory()), entry);
        }
        long now = System.currentTimeMillis();
        for (String memory : memories) {
            String normalized = normalize(memory);
            if (normalized.isBlank() || byText.containsKey(normalized)) {
                continue;
            }
            LongTermMemoryEntry entry = new LongTermMemoryEntry(newUid(now), normalized, now, 0L, 0, 0.5D);
            byText.put(normalized, entry);
        }
        write(scope, new ArrayList<>(byText.values()));
    }

    public void recordHits(AssistantScope scope, List<String> uids) {
        if (!writable(scope) || uids == null || uids.isEmpty()) {
            return;
        }
        List<LongTermMemoryEntry> entries = load(scope);
        if (entries.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<LongTermMemoryEntry> updated = new ArrayList<>();
        for (LongTermMemoryEntry entry : entries) {
            updated.add(uids.contains(entry.uid()) ? entry.recordHit(now) : entry);
        }
        write(scope, retain(updated));
    }

    public void cleanup(AssistantScope scope) {
        if (!writable(scope)) {
            return;
        }
        List<LongTermMemoryEntry> retained = retain(load(scope));
        write(scope, retained);
    }

    private List<LongTermMemoryEntry> retain(List<LongTermMemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<LongTermMemoryEntry> alive = entries.stream()
                .filter(entry -> entry != null && !entry.isEmpty())
                .filter(entry -> ttlMillis <= 0L || entry.hitCount() > 0 || now - entry.createdAt() <= ttlMillis)
                .sorted((left, right) -> Double.compare(retentionScore(right, now), retentionScore(left, now)))
                .limit(maxEntries)
                .toList();
        return alive.stream()
                .sorted((left, right) -> Long.compare(left.createdAt(), right.createdAt()))
                .toList();
    }

    private double retentionScore(LongTermMemoryEntry entry, long now) {
        long lastSignal = Math.max(entry.createdAt(), entry.lastHitAt());
        double ageDays = Math.max(0.0D, (now - lastSignal) / 86400000.0D);
        return entry.importance() * 10.0D + entry.hitCount() * 2.0D - ageDays;
    }

    private void write(AssistantScope scope, List<LongTermMemoryEntry> entries) {
        List<JsonObject> metadata = entries == null ? List.of() : entries.stream()
                .filter(entry -> entry != null && !entry.isEmpty())
                .map(LongTermMemoryEntry::toJson)
                .toList();
        List<JsonObject> server = entries == null ? List.of() : entries.stream()
                .filter(entry -> entry != null && !entry.isEmpty())
                .map(LongTermMemoryEntry::toServerJson)
                .toList();
        jsonStore.writeJsonLines(metadataFile(scope), metadata);
        jsonStore.writeJsonLines(layout.memoryRagFile(scope), server);
    }

    private Path metadataFile(AssistantScope scope) {
        return layout.memoryRagRoot(scope).resolve("memories_meta.jsonl");
    }

    private boolean writable(AssistantScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String newUid(long now) {
        return "mem-" + Long.toUnsignedString(now, 36) + "-" + Long.toUnsignedString(RANDOM.nextLong(), 36);
    }
}
