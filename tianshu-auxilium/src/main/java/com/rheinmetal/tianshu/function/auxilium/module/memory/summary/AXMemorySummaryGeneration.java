package com.rheinmetal.tianshu.function.auxilium.module.memory.summary;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 摘要块账本的一条记录：一次换代把哪些单元摘要晋升进了块。
 *
 * <p>账本按代次 append-only：第 {@code generation} 代晋升的摘要 id 一旦写入就不再修改。
 * "块里有什么"因此是<b>可重放的派生量</b>——读账本 + 读摘要库即可还原，不需要额外的可变状态。
 * 块内顺序约定为 <b>新→旧</b>：代次大的在前，同一代里 id 列表的书写顺序就是新→旧。</p>
 */
public record AXMemorySummaryGeneration(
        int schemaVersion,
        int generation,
        List<String> stmIds,
        long createdAtMillis
) {
    public static final int SCHEMA_VERSION = 1;

    public AXMemorySummaryGeneration {
        schemaVersion = schemaVersion <= 0 ? SCHEMA_VERSION : schemaVersion;
        generation = Math.max(0, generation);
        stmIds = stmIds == null ? List.of() : stmIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        createdAtMillis = createdAtMillis <= 0L ? System.currentTimeMillis() : createdAtMillis;
    }

    public boolean isEmpty() {
        return generation <= 0 || stmIds.isEmpty();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", schemaVersion);
        json.addProperty("generation", generation);
        json.addProperty("createdAtMillis", createdAtMillis);
        JsonArray ids = new JsonArray();
        stmIds.forEach(ids::add);
        json.add("stmIds", ids);
        return json;
    }

    public static AXMemorySummaryGeneration fromJson(JsonObject json) {
        if (json == null) {
            return new AXMemorySummaryGeneration(SCHEMA_VERSION, 0, List.of(), 0L);
        }
        return new AXMemorySummaryGeneration(
                readInt(json, "schemaVersion", SCHEMA_VERSION),
                readInt(json, "generation", 0),
                readStringArray(json, "stmIds"),
                readLong(json, "createdAtMillis", 0L)
        );
    }

    private static int readInt(JsonObject json, String key, int fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : fallback;
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
