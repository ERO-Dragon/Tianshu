package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.google.gson.JsonObject;

public record AXMemoryStatsSnapshot(
        int schemaVersion,
        String worldId,
        long generatedAtMillis,
        int stmBlockCount,
        int memoryEventCount,
        int vectorCount,
        int stmTokenCount,
        long earliestEventMillis,
        long latestEventMillis,
        int generation,
        int summariesInBlock,
        int summariesDroppedTotal,
        int summaryBlockTokens
) {
    public static final int SCHEMA_VERSION = 1;

    public AXMemoryStatsSnapshot {
        schemaVersion = schemaVersion <= 0 ? SCHEMA_VERSION : schemaVersion;
        worldId = worldId == null || worldId.isBlank() ? "unknown_world" : worldId.trim();
        generatedAtMillis = generatedAtMillis <= 0L ? System.currentTimeMillis() : generatedAtMillis;
        stmBlockCount = Math.max(0, stmBlockCount);
        memoryEventCount = Math.max(0, memoryEventCount);
        vectorCount = Math.max(0, vectorCount);
        stmTokenCount = Math.max(0, stmTokenCount);
        earliestEventMillis = Math.max(0L, earliestEventMillis);
        latestEventMillis = Math.max(earliestEventMillis, latestEventMillis);
        generation = Math.max(0, generation);
        summariesInBlock = Math.max(0, summariesInBlock);
        summariesDroppedTotal = Math.max(0, summariesDroppedTotal);
        summaryBlockTokens = Math.max(0, summaryBlockTokens);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", schemaVersion);
        json.addProperty("worldId", worldId);
        json.addProperty("generatedAtMillis", generatedAtMillis);
        json.addProperty("stmBlockCount", stmBlockCount);
        json.addProperty("memoryEventCount", memoryEventCount);
        json.addProperty("vectorCount", vectorCount);
        json.addProperty("stmTokenCount", stmTokenCount);
        json.addProperty("earliestEventMillis", earliestEventMillis);
        json.addProperty("latestEventMillis", latestEventMillis);
        // 摘要块观测（记忆设计 §7）：块里几份、挤掉几份、占多少 token、当前第几代。
        json.addProperty("generation", generation);
        json.addProperty("summariesInBlock", summariesInBlock);
        json.addProperty("summariesDroppedTotal", summariesDroppedTotal);
        json.addProperty("summaryBlockTokens", summaryBlockTokens);
        return json;
    }

    public static AXMemoryStatsSnapshot fromJson(JsonObject json) {
        if (json == null) {
            return empty("unknown_world");
        }
        return new AXMemoryStatsSnapshot(
                readInt(json, "schemaVersion", SCHEMA_VERSION),
                readString(json, "worldId"),
                readLong(json, "generatedAtMillis", 0L),
                readInt(json, "stmBlockCount", 0),
                readInt(json, "memoryEventCount", 0),
                readInt(json, "vectorCount", 0),
                readInt(json, "stmTokenCount", 0),
                readLong(json, "earliestEventMillis", 0L),
                readLong(json, "latestEventMillis", 0L),
                readInt(json, "generation", 0),
                readInt(json, "summariesInBlock", 0),
                readInt(json, "summariesDroppedTotal", 0),
                readInt(json, "summaryBlockTokens", 0)
        );
    }

    public static AXMemoryStatsSnapshot empty(String worldId) {
        return new AXMemoryStatsSnapshot(SCHEMA_VERSION, worldId, System.currentTimeMillis(), 0, 0, 0, 0, 0L, 0L, 0, 0, 0, 0);
    }

    private static String readString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
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
}
