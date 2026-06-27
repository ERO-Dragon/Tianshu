package com.rheinmetal.tianshu.function.auxilium.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.auxilium.storage.AXHashing;

import java.util.ArrayList;
import java.util.List;

public record AXStmBlock(
        String id,
        String contentHash,
        String worldId,
        long createdAtMillis,
        long sourceFromMillis,
        long sourceToMillis,
        String previousStmId,
        String nextStmId,
        int sourceTurnCount,
        int estimatedTokens,
        String content,
        List<String> attachedEventIds
) {
    public static final int SCHEMA_VERSION = 1;

    public AXStmBlock {
        content = content == null ? "" : content.trim();
        worldId = worldId == null || worldId.isBlank() ? "unknown_world" : worldId.trim();
        createdAtMillis = createdAtMillis <= 0L ? System.currentTimeMillis() : createdAtMillis;
        sourceFromMillis = Math.max(0L, sourceFromMillis);
        sourceToMillis = Math.max(sourceFromMillis, sourceToMillis);
        previousStmId = previousStmId == null ? "" : previousStmId.trim();
        nextStmId = nextStmId == null ? "" : nextStmId.trim();
        sourceTurnCount = Math.max(0, sourceTurnCount);
        estimatedTokens = estimatedTokens <= 0 ? new AXTokenEstimator().estimate(content) : estimatedTokens;
        contentHash = contentHash == null || contentHash.isBlank() ? AXHashing.sha256Short(content) : contentHash.trim();
        id = id == null || id.isBlank()
                ? "stm_" + Long.toUnsignedString(createdAtMillis, 36) + "_" + AXHashing.sha256Short(worldId + "\n" + sourceFromMillis + "\n" + sourceToMillis + "\n" + content)
                : id.trim();
        attachedEventIds = attachedEventIds == null ? List.of() : attachedEventIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public boolean isEmpty() {
        return id.isBlank() || content.isBlank();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("id", id);
        json.addProperty("contentHash", contentHash);
        json.addProperty("worldId", worldId);
        json.addProperty("createdAtMillis", createdAtMillis);
        json.addProperty("sourceFromMillis", sourceFromMillis);
        json.addProperty("sourceToMillis", sourceToMillis);
        json.addProperty("previousStmId", previousStmId);
        json.addProperty("nextStmId", nextStmId);
        json.addProperty("sourceTurnCount", sourceTurnCount);
        json.addProperty("estimatedTokens", estimatedTokens);
        json.addProperty("content", content);
        JsonArray eventIds = new JsonArray();
        attachedEventIds.forEach(eventIds::add);
        json.add("attachedEventIds", eventIds);
        return json;
    }

    public static AXStmBlock fromJson(JsonObject json) {
        if (json == null) {
            return new AXStmBlock("", "", "", 0L, 0L, 0L, "", "", 0, 0, "", List.of());
        }
        return new AXStmBlock(
                readString(json, "id"),
                readString(json, "contentHash"),
                readString(json, "worldId"),
                readLong(json, "createdAtMillis", readLong(json, "createdAt", 0L)),
                readLong(json, "sourceFromMillis", readLong(json, "fromTurnCreatedAt", 0L)),
                readLong(json, "sourceToMillis", readLong(json, "toTurnCreatedAt", 0L)),
                readString(json, "previousStmId"),
                readString(json, "nextStmId"),
                readInt(json, "sourceTurnCount", 0),
                readInt(json, "estimatedTokens", readInt(json, "sourceEstimatedTokens", 0)),
                readString(json, "content"),
                readStringArray(json, "attachedEventIds")
        );
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
