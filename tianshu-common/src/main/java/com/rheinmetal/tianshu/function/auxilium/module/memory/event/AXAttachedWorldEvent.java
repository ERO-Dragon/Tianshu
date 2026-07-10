package com.rheinmetal.tianshu.function.auxilium.module.memory.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.auxilium.storage.AXHashing;

import java.util.ArrayList;
import java.util.List;

public record AXAttachedWorldEvent(
        String id,
        String eventType,
        String dedupKey,
        String worldId,
        String dimension,
        String position,
        long happenedAtMillis,
        String nativeId,
        String text,
        List<String> tags
) {
    public static final int SCHEMA_VERSION = 1;

    public AXAttachedWorldEvent {
        eventType = sanitize(eventType, "unknown");
        worldId = sanitize(worldId, "unknown_world");
        dimension = sanitize(dimension, "");
        position = sanitize(position, "");
        nativeId = sanitize(nativeId, "");
        text = sanitize(text, "");
        happenedAtMillis = happenedAtMillis <= 0L ? System.currentTimeMillis() : happenedAtMillis;
        tags = tags == null ? List.of() : tags.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        dedupKey = dedupKey == null || dedupKey.isBlank()
                ? defaultDedupKey(eventType, worldId, dimension, position, nativeId, text)
                : dedupKey.trim();
        id = id == null || id.isBlank()
                ? "awe_" + Long.toUnsignedString(happenedAtMillis, 36) + "_" + AXHashing.sha256Short(dedupKey)
                : id.trim();
    }

    public boolean isEmpty() {
        return id.isBlank() || eventType.isBlank();
    }

    public AXMemoryEvent toMemoryEvent(String stmId, String sourceKind) {
        String fact = text.isBlank() ? eventType : text;
        return new AXMemoryEvent(
                "",
                fact,
                "",
                stmId,
                sourceKind == null || sourceKind.isBlank() ? "attached_world_event" : sourceKind,
                worldId,
                dimension,
                position,
                !position.isBlank(),
                System.currentTimeMillis(),
                happenedAtMillis,
                tags
        );
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("id", id);
        json.addProperty("eventType", eventType);
        json.addProperty("dedupKey", dedupKey);
        json.addProperty("worldId", worldId);
        json.addProperty("dimension", dimension);
        json.addProperty("position", position);
        json.addProperty("happenedAtMillis", happenedAtMillis);
        json.addProperty("nativeId", nativeId);
        json.addProperty("text", text);
        JsonArray tagArray = new JsonArray();
        tags.forEach(tagArray::add);
        json.add("tags", tagArray);
        return json;
    }

    public static AXAttachedWorldEvent fromJson(JsonObject json) {
        if (json == null) {
            return new AXAttachedWorldEvent("", "", "", "", "", "", 0L, "", "", List.of());
        }
        return new AXAttachedWorldEvent(
                readString(json, "id"),
                readString(json, "eventType"),
                readString(json, "dedupKey"),
                readString(json, "worldId"),
                readString(json, "dimension"),
                readString(json, "position"),
                readLong(json, "happenedAtMillis", readLong(json, "eventAtMillis", 0L)),
                readString(json, "nativeId"),
                readString(json, "text"),
                readStringArray(json, "tags")
        );
    }

    private static String defaultDedupKey(String eventType, String worldId, String dimension, String position, String nativeId, String text) {
        String stableNative = nativeId == null ? "" : nativeId.trim();
        if (!stableNative.isBlank()) {
            return eventType + "\n" + worldId + "\n" + stableNative;
        }
        return eventType + "\n" + worldId + "\n" + dimension + "\n" + position + "\n" + text;
    }

    private static String sanitize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String readString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    private static long readLong(JsonObject json, String key, long fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsLong() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static List<String> readStringArray(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
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
        return values;
    }
}
