package com.rheinmetal.tianshu.function.auxilium.module.memory.preference;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.auxilium.storage.AXHashing;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条玩家偏好。
 *
 * <p><b>归属键是玩家 UUID（{@link #playerId()}），不是世界</b>：换存档、换维度、换服务器世界，偏好
 * 仍然是同一个玩家的偏好（记忆设计 §4.5）。因此该文件落在 {@code players/{playerId}/} 下，键里
 * 不含 worldId。</p>
 *
 * <p>偏好是 append-only 的：同一句话在不同轮次被再次明示就是两条记录，靠 {@link #turnRefs()}
 * 与 {@link #createdAtMillis()} 区分，写入侧不做任何去重。</p>
 */
public record AXPlayerPreference(
        String id,
        String playerId,
        String fact,
        double confidence,
        List<String> turnRefs,
        String batchId,
        long createdAtMillis
) {
    public static final int SCHEMA_VERSION = 1;

    public static final double MIN_CONFIDENCE = 0.0D;
    public static final double MAX_CONFIDENCE = 1.0D;

    public AXPlayerPreference {
        playerId = playerId == null || playerId.isBlank() ? "default_user" : playerId.trim();
        fact = fact == null ? "" : fact.trim();
        confidence = Math.max(MIN_CONFIDENCE, Math.min(MAX_CONFIDENCE, confidence));
        turnRefs = normalizeValues(turnRefs);
        batchId = batchId == null ? "" : batchId.trim();
        createdAtMillis = createdAtMillis <= 0L ? System.currentTimeMillis() : createdAtMillis;
        id = id == null || id.isBlank() ? buildId(playerId, fact, turnRefs, createdAtMillis) : id.trim();
    }

    private static String buildId(String playerId, String fact, List<String> turnRefs, long createdAtMillis) {
        String seed = playerId + "\n" + String.join(",", turnRefs) + "\n" + fact + "\n" + createdAtMillis;
        return "pref_" + Long.toUnsignedString(createdAtMillis, 36) + "_" + AXHashing.sha256Short(seed);
    }

    private static List<String> normalizeValues(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public boolean isEmpty() {
        return fact.isBlank();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("id", id);
        json.addProperty("playerId", playerId);
        json.addProperty("fact", fact);
        json.addProperty("confidence", confidence);
        json.addProperty("batchId", batchId);
        json.addProperty("createdAtMillis", createdAtMillis);
        JsonArray refs = new JsonArray();
        turnRefs.forEach(refs::add);
        json.add("turnRefs", refs);
        return json;
    }

    public static AXPlayerPreference fromJson(JsonObject json) {
        if (json == null) {
            return new AXPlayerPreference("", "", "", 0.0D, List.of(), "", 0L);
        }
        return new AXPlayerPreference(
                readString(json, "id"),
                readString(json, "playerId"),
                readString(json, "fact"),
                readDouble(json, "confidence", 0.0D),
                readStringArray(json, "turnRefs"),
                readString(json, "batchId"),
                readLong(json, "createdAtMillis", 0L)
        );
    }

    private static String readString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    private static double readDouble(JsonObject json, String key, double fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsDouble() : fallback;
        } catch (Exception e) {
            return fallback;
        }
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
