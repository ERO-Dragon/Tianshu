package com.rheinmetal.tianshu.function.auxilium.module.memory.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.auxilium.storage.AXHashing;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条客观事件。
 *
 * <p><b>事件是客观事实，入库时永不去重、永不合并</b>：同一件事发生两次就是两条事件，靠
 * {@link #turnRefs()}（轮级溯源）区分。{@link #importance()} 是抽取阶段由模型给出的一次性静态分，
 * 落库后不改写，也不在检索时派生。</p>
 */
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
        List<String> entityTags,
        List<String> turnRefs,
        int importance
) {
    /** 只加字段、不改版本号：旧数据缺 {@code turnRefs}/{@code importance} 时按空溯源与兜底分读取。 */
    public static final int SCHEMA_VERSION = 1;

    public static final int MIN_IMPORTANCE = 1;
    public static final int MAX_IMPORTANCE = 10;

    /** 模型没给分（或给了非整数）时的兜底分：中性值，不偏向重要也不偏向噪声。 */
    public static final int DEFAULT_IMPORTANCE = 5;

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
        entityTags = normalizeValues(entityTags);
        turnRefs = normalizeValues(turnRefs);
        importance = Math.max(MIN_IMPORTANCE, Math.min(MAX_IMPORTANCE, importance));
        id = id == null || id.isBlank() ? buildId(worldId, stmId, turnRefs, fact, happenedAtMillis) : id.trim();
    }

    /**
     * 事件 id。
     *
     * <p>源轮次算进种子：同一句事实来自不同轮次 ⇒ 不同 id ⇒ 两条独立事件（这正是"不去重"）；
     * 同一批次重放 ⇒ 同一 id（幂等，不是去重）。</p>
     */
    private static String buildId(String worldId, String stmId, List<String> turnRefs, String fact, long happenedAtMillis) {
        String seed = worldId + "\n" + stmId + "\n" + String.join(",", turnRefs) + "\n" + fact;
        return "evt_" + Long.toUnsignedString(happenedAtMillis, 36) + "_" + AXHashing.sha256Short(seed);
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
        json.addProperty("importance", importance);
        JsonArray tags = new JsonArray();
        entityTags.forEach(tags::add);
        json.add("entityTags", tags);
        JsonArray refs = new JsonArray();
        turnRefs.forEach(refs::add);
        json.add("turnRefs", refs);
        return json;
    }

    public static AXMemoryEvent fromJson(JsonObject json) {
        if (json == null) {
            return new AXMemoryEvent("", "", "", "", "", "", "", "", false, 0L, 0L, List.of(), List.of(), DEFAULT_IMPORTANCE);
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
                readStringArray(json, "entityTags"),
                readStringArray(json, "turnRefs"),
                readInt(json, "importance", DEFAULT_IMPORTANCE)
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
