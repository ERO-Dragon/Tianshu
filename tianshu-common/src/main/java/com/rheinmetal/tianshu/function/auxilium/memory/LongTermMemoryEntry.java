package com.rheinmetal.tianshu.function.auxilium.memory;

import com.google.gson.JsonObject;

public record LongTermMemoryEntry(
        String uid,
        String longTermMemory,
        long createdAt,
        long lastHitAt,
        int hitCount,
        double importance
) {
    public LongTermMemoryEntry {
        uid = uid == null ? "" : uid.trim();
        longTermMemory = longTermMemory == null ? "" : longTermMemory.trim();
        createdAt = createdAt <= 0L ? System.currentTimeMillis() : createdAt;
        lastHitAt = Math.max(0L, lastHitAt);
        hitCount = Math.max(0, hitCount);
        importance = Double.isNaN(importance) || Double.isInfinite(importance) ? 0.0D : Math.max(0.0D, Math.min(1.0D, importance));
    }

    public boolean isEmpty() {
        return uid.isBlank() || longTermMemory.isBlank();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("uid", uid);
        json.addProperty("long_term_memory", longTermMemory);
        json.addProperty("created_at", createdAt);
        json.addProperty("last_hit_at", lastHitAt);
        json.addProperty("hit_count", hitCount);
        json.addProperty("importance", importance);
        return json;
    }

    public JsonObject toServerJson() {
        JsonObject json = new JsonObject();
        json.addProperty("uid", uid);
        json.addProperty("long_term_memory", longTermMemory);
        return json;
    }

    public LongTermMemoryEntry recordHit(long timeMillis) {
        return new LongTermMemoryEntry(uid, longTermMemory, createdAt, timeMillis <= 0L ? System.currentTimeMillis() : timeMillis, hitCount + 1, importance);
    }

    public static LongTermMemoryEntry fromJson(JsonObject json) {
        if (json == null) {
            return new LongTermMemoryEntry("", "", 0L, 0L, 0, 0.0D);
        }
        String uid = readString(json, "uid");
        String memory = readString(json, "long_term_memory");
        if (memory.isBlank()) {
            memory = readString(json, "text");
        }
        long createdAt = readLong(json, "created_at", readLong(json, "createdAt", System.currentTimeMillis()));
        long lastHitAt = readLong(json, "last_hit_at", 0L);
        int hitCount = readInt(json, "hit_count", 0);
        double importance = readDouble(json, "importance", 0.0D);
        return new LongTermMemoryEntry(uid, memory, createdAt, lastHitAt, hitCount, importance);
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

    private static double readDouble(JsonObject json, String key, double fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsDouble() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
