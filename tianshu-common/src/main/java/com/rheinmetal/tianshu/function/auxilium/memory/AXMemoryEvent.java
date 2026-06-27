package com.rheinmetal.tianshu.function.auxilium.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.auxilium.storage.AXHashing;

import java.util.ArrayList;
import java.util.List;

public record AXMemoryEvent(
        String id,
        String fact,
        String factHash,
        String stmId,
        String sourceKind,
        String worldId,
        String dimension,
        String position,
        boolean spatiallyBound,
        long createdAtMillis,
        long happenedAtMillis,
        int estimatedTokens,
        List<String> entityTags
) {
    public static final int SCHEMA_VERSION = 1;

    public AXMemoryEvent {
        fact = fact == null ? "" : fact.trim();
        factHash = factHash == null || factHash.isBlank() ? AXHashing.sha256Short(fact) : factHash.trim();
        stmId = stmId == null ? "" : stmId.trim();
        sourceKind = sourceKind == null || sourceKind.isBlank() ? "stm_fact" : sourceKind.trim();
        worldId = worldId == null || worldId.isBlank() ? "unknown_world" : worldId.trim();
        dimension = dimension == null ? "" : dimension.trim();
        position = position == null ? "" : position.trim();
        createdAtMillis = createdAtMillis <= 0L ? System.currentTimeMillis() : createdAtMillis;
        happenedAtMillis = happenedAtMillis <= 0L ? createdAtMillis : happenedAtMillis;
        estimatedTokens = estimatedTokens <= 0 ? new AXTokenEstimator().estimate(fact) : estimatedTokens;
        entityTags = entityTags == null ? List.of() : entityTags.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        id = id == null || id.isBlank()
                ? "evt_" + Long.toUnsignedString(happenedAtMillis, 36) + "_" + AXHashing.sha256Short(worldId + "\n" + stmId + "\n" + fact)
                : id.trim();
    }

    public boolean isEmpty() {
        return id.isBlank() || fact.isBlank();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("id", id);
        json.addProperty("fact", fact);
        json.addProperty("factHash", factHash);
        json.addProperty("stmId", stmId);
        json.addProperty("sourceKind", sourceKind);
        json.addProperty("worldId", worldId);
        json.addProperty("dimension", dimension);
        json.addProperty("position", position);
        json.addProperty("spatiallyBound", spatiallyBound);
        json.addProperty("createdAtMillis", createdAtMillis);
        json.addProperty("happenedAtMillis", happenedAtMillis);
        json.addProperty("estimatedTokens", estimatedTokens);
        JsonArray tags = new JsonArray();
        entityTags.forEach(tags::add);
        json.add("entityTags", tags);
        return json;
    }

    public static AXMemoryEvent fromJson(JsonObject json) {
        if (json == null) {
            return new AXMemoryEvent("", "", "", "", "", "", "", "", false, 0L, 0L, 0, List.of());
        }
        return new AXMemoryEvent(
                readString(json, "id"),
                readString(json, "fact"),
                readString(json, "factHash"),
                readString(json, "stmId"),
                readString(json, "sourceKind"),
                readString(json, "worldId"),
                readString(json, "dimension"),
                readString(json, "position"),
                readBoolean(json, "spatiallyBound"),
                readLong(json, "createdAtMillis", 0L),
                readLong(json, "happenedAtMillis", readLong(json, "eventAtMillis", 0L)),
                readInt(json, "estimatedTokens", 0),
                readStringArray(json, "entityTags")
        );
    }

    private static String readString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    private static boolean readBoolean(JsonObject json, String key) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() && json.get(key).getAsBoolean();
        } catch (Exception e) {
            return false;
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
