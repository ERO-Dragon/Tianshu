package com.rheinmetal.tianshu.function.auxilium.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AXMemoryStorageManifestStore {
    public static final int MANIFEST_SCHEMA_VERSION = 1;
    public static final int LAYOUT_VERSION = 1;

    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;
    private final Set<String> ensuredWorlds = ConcurrentHashMap.newKeySet();

    public AXMemoryStorageManifestStore(AXStorageLayout layout, AXJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public void ensureWorldManifest(AXScope scope) {
        if (scope == null || !scope.writable() || layout == null || jsonStore == null) {
            return;
        }
        String key = scope.worldId();
        if (!ensuredWorlds.add(key)) {
            return;
        }
        Path path = layout.worldManifestFile(scope);
        JsonObject manifest = jsonStore.readObject(path).map(JsonObject::deepCopy).orElseGet(JsonObject::new);
        boolean changed = false;
        changed |= addString(manifest, "kind", "ax.world_memory");
        changed |= addInt(manifest, "manifestSchemaVersion", MANIFEST_SCHEMA_VERSION);
        changed |= addInt(manifest, "layoutVersion", LAYOUT_VERSION);
        changed |= addLong(manifest, "createdAtMillis", System.currentTimeMillis());
        changed |= addString(manifest, "worldId", scope.worldId());
        changed |= addString(manifest, "worldName", scope.displayName());
        changed |= ensureSchemas(manifest);
        changed |= ensureFiles(manifest);
        changed |= ensureDerivedArtifacts(manifest);
        if (changed) {
            jsonStore.writeObject(path, manifest);
        }
    }

    public static Map<String, Integer> currentSchemas() {
        Map<String, Integer> schemas = new LinkedHashMap<>();
        schemas.put("stmBlock", AXStmBlock.SCHEMA_VERSION);
        schemas.put("memoryEvent", AXMemoryEvent.SCHEMA_VERSION);
        schemas.put("attachedWorldEvent", AXAttachedWorldEvent.SCHEMA_VERSION);
        schemas.put("eventVector", AXEventVector.SCHEMA_VERSION);
        return Map.copyOf(schemas);
    }

    public static Map<String, String> requiredFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("stmBlocks", "stm_blocks/stm_blocks.jsonl");
        files.put("events", "events/events.jsonl");
        files.put("attachedWorldEvents", "events/attached_world_events.jsonl");
        files.put("eventVectors", "vectors/<embeddingNamespace>/event_vectors.jsonl");
        return Map.copyOf(files);
    }

    public static List<String> appendOnlyFileKeys() {
        return List.of("stmBlocks", "events", "attachedWorldEvents", "eventVectors");
    }

    public static List<String> rebuildableArtifactKeys() {
        return List.of("retrievalIndex", "l1Clusters", "l2EffectiveMappings", "entityGraph", "storageStats");
    }

    private boolean ensureSchemas(JsonObject manifest) {
        JsonObject schemas = object(manifest, "schemas");
        boolean changed = !manifest.has("schemas");
        for (Map.Entry<String, Integer> schema : currentSchemas().entrySet()) {
            changed |= addInt(schemas, schema.getKey(), schema.getValue());
        }
        manifest.add("schemas", schemas);
        return changed;
    }

    private boolean ensureFiles(JsonObject manifest) {
        JsonObject files = object(manifest, "files");
        boolean changed = !manifest.has("files");
        for (Map.Entry<String, String> file : requiredFiles().entrySet()) {
            changed |= addString(files, file.getKey(), file.getValue());
        }
        changed |= addArray(files, "appendOnly", appendOnlyFileKeys());
        manifest.add("files", files);
        return changed;
    }

    private boolean ensureDerivedArtifacts(JsonObject manifest) {
        JsonObject derived = object(manifest, "derivedArtifacts");
        boolean changed = !manifest.has("derivedArtifacts");
        changed |= addBoolean(derived, "authority", false);
        changed |= addString(derived, "root", "indexes/");
        changed |= addString(derived, "statsFile", "stats/memory_stats.json");
        changed |= addArray(derived, "rebuildable", rebuildableArtifactKeys());
        manifest.add("derivedArtifacts", derived);
        return changed;
    }

    private JsonObject object(JsonObject json, String key) {
        if (json.has(key) && json.get(key).isJsonObject()) {
            return json.getAsJsonObject(key);
        }
        return new JsonObject();
    }

    private boolean addString(JsonObject json, String key, String value) {
        if (json.has(key)) {
            return false;
        }
        json.addProperty(key, value == null ? "" : value);
        return true;
    }

    private boolean addInt(JsonObject json, String key, int value) {
        if (json.has(key)) {
            return false;
        }
        json.addProperty(key, value);
        return true;
    }

    private boolean addLong(JsonObject json, String key, long value) {
        if (json.has(key)) {
            return false;
        }
        json.addProperty(key, Math.max(0L, value));
        return true;
    }

    private boolean addBoolean(JsonObject json, String key, boolean value) {
        if (json.has(key)) {
            return false;
        }
        json.addProperty(key, value);
        return true;
    }

    private boolean addArray(JsonObject json, String key, List<String> values) {
        if (json.has(key)) {
            return false;
        }
        JsonArray array = new JsonArray();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    array.add(value);
                }
            }
        }
        json.add(key, array);
        return true;
    }
}
