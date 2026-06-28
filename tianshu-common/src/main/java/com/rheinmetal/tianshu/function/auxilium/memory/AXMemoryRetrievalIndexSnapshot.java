package com.rheinmetal.tianshu.function.auxilium.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AXMemoryRetrievalIndexSnapshot(
        int schemaVersion,
        String embeddingNamespace,
        long generatedAtMillis,
        long eventsSize,
        long eventsModifiedAtMillis,
        long vectorsSize,
        long vectorsModifiedAtMillis,
        int eventCount,
        int vectorCount,
        List<ClusterSnapshot> l1Clusters,
        List<ClusterSnapshot> l2EffectiveMappings,
        Map<String, String> effectiveMappingByEventId
) {
    public static final int SCHEMA_VERSION = 1;

    public AXMemoryRetrievalIndexSnapshot {
        schemaVersion = schemaVersion <= 0 ? SCHEMA_VERSION : schemaVersion;
        embeddingNamespace = embeddingNamespace == null ? "" : embeddingNamespace.trim();
        generatedAtMillis = Math.max(0L, generatedAtMillis);
        eventsSize = Math.max(-1L, eventsSize);
        eventsModifiedAtMillis = Math.max(-1L, eventsModifiedAtMillis);
        vectorsSize = Math.max(-1L, vectorsSize);
        vectorsModifiedAtMillis = Math.max(-1L, vectorsModifiedAtMillis);
        eventCount = Math.max(0, eventCount);
        vectorCount = Math.max(0, vectorCount);
        l1Clusters = l1Clusters == null ? List.of() : List.copyOf(l1Clusters);
        l2EffectiveMappings = l2EffectiveMappings == null ? List.of() : List.copyOf(l2EffectiveMappings);
        effectiveMappingByEventId = effectiveMappingByEventId == null ? Map.of() : Map.copyOf(effectiveMappingByEventId);
    }

    public boolean matches(String namespace, AXMemoryRetrievalIndexCache.SourceStamp stamp) {
        if (schemaVersion != SCHEMA_VERSION || stamp == null) {
            return false;
        }
        return embeddingNamespace.equals(namespace == null ? "" : namespace.trim())
                && eventsSize == stamp.eventsSize()
                && eventsModifiedAtMillis == stamp.eventsModifiedAtMillis()
                && vectorsSize == stamp.vectorsSize()
                && vectorsModifiedAtMillis == stamp.vectorsModifiedAtMillis();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", schemaVersion);
        json.addProperty("embeddingNamespace", embeddingNamespace);
        json.addProperty("generatedAtMillis", generatedAtMillis);
        json.addProperty("eventsSize", eventsSize);
        json.addProperty("eventsModifiedAtMillis", eventsModifiedAtMillis);
        json.addProperty("vectorsSize", vectorsSize);
        json.addProperty("vectorsModifiedAtMillis", vectorsModifiedAtMillis);
        json.addProperty("eventCount", eventCount);
        json.addProperty("vectorCount", vectorCount);
        json.add("l1Clusters", writeClusters(l1Clusters));
        json.add("l2EffectiveMappings", writeClusters(l2EffectiveMappings));
        JsonObject mappings = new JsonObject();
        effectiveMappingByEventId.forEach((eventId, mappingId) -> mappings.addProperty(eventId, mappingId));
        json.add("effectiveMappingByEventId", mappings);
        return json;
    }

    public static AXMemoryRetrievalIndexSnapshot fromJson(JsonObject json) {
        if (json == null) {
            return empty();
        }
        return new AXMemoryRetrievalIndexSnapshot(
                readInt(json, "schemaVersion", 0),
                readString(json, "embeddingNamespace"),
                readLong(json, "generatedAtMillis", 0L),
                readLong(json, "eventsSize", -1L),
                readLong(json, "eventsModifiedAtMillis", -1L),
                readLong(json, "vectorsSize", -1L),
                readLong(json, "vectorsModifiedAtMillis", -1L),
                readInt(json, "eventCount", 0),
                readInt(json, "vectorCount", 0),
                readClusters(json, "l1Clusters"),
                readClusters(json, "l2EffectiveMappings"),
                readStringMap(json, "effectiveMappingByEventId")
        );
    }

    public static AXMemoryRetrievalIndexSnapshot empty() {
        return new AXMemoryRetrievalIndexSnapshot(0, "", 0L, -1L, -1L, -1L, -1L, 0, 0, List.of(), List.of(), Map.of());
    }

    private static JsonArray writeClusters(List<ClusterSnapshot> clusters) {
        JsonArray array = new JsonArray();
        if (clusters != null) {
            for (ClusterSnapshot cluster : clusters) {
                if (cluster != null && !cluster.isEmpty()) {
                    array.add(cluster.toJson());
                }
            }
        }
        return array;
    }

    private static List<ClusterSnapshot> readClusters(JsonObject json, String key) {
        if (json == null || !json.has(key) || !json.get(key).isJsonArray()) {
            return List.of();
        }
        List<ClusterSnapshot> clusters = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray(key)) {
            if (element != null && element.isJsonObject()) {
                ClusterSnapshot cluster = ClusterSnapshot.fromJson(element.getAsJsonObject());
                if (!cluster.isEmpty()) {
                    clusters.add(cluster);
                }
            }
        }
        return List.copyOf(clusters);
    }

    private static Map<String, String> readStringMap(JsonObject json, String key) {
        if (json == null || !json.has(key) || !json.get(key).isJsonObject()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        JsonObject object = json.getAsJsonObject(key);
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String value = entry.getValue() == null || entry.getValue().isJsonNull() ? "" : entry.getValue().getAsString();
            if (entry.getKey() != null && !entry.getKey().isBlank() && value != null && !value.isBlank()) {
                result.put(entry.getKey().trim(), value.trim());
            }
        }
        return Map.copyOf(result);
    }

    private static String readString(JsonObject json, String key) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static long readLong(JsonObject json, String key, long fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsLong() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int readInt(JsonObject json, String key, int fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    public record ClusterSnapshot(
            String id,
            String level,
            List<String> eventIds,
            List<String> entityTags
    ) {
        public ClusterSnapshot {
            id = id == null ? "" : id.trim();
            level = level == null ? "" : level.trim();
            eventIds = eventIds == null ? List.of() : eventIds.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
            entityTags = entityTags == null ? List.of() : entityTags.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
        }

        boolean isEmpty() {
            return id.isBlank() || eventIds.isEmpty();
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("level", level);
            json.add("eventIds", writeStringArray(eventIds));
            json.add("entityTags", writeStringArray(entityTags));
            return json;
        }

        static ClusterSnapshot fromJson(JsonObject json) {
            if (json == null) {
                return new ClusterSnapshot("", "", List.of(), List.of());
            }
            return new ClusterSnapshot(
                    readString(json, "id"),
                    readString(json, "level"),
                    readStringArray(json, "eventIds"),
                    readStringArray(json, "entityTags")
            );
        }

        private static JsonArray writeStringArray(List<String> values) {
            JsonArray array = new JsonArray();
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.isBlank()) {
                        array.add(value.trim());
                    }
                }
            }
            return array;
        }

        private static List<String> readStringArray(JsonObject json, String key) {
            if (json == null || !json.has(key) || !json.get(key).isJsonArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray(key)) {
                if (element != null && !element.isJsonNull()) {
                    String value = element.getAsString();
                    if (value != null && !value.isBlank()) {
                        values.add(value.trim());
                    }
                }
            }
            return List.copyOf(values);
        }

    }
}
