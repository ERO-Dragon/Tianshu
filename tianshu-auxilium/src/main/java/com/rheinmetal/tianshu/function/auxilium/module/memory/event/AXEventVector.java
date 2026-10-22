package com.rheinmetal.tianshu.function.auxilium.module.memory.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public record AXEventVector(
        String eventId,
        String eventFactHash,
        String embeddingModelName,
        String embeddingNamespace,
        int dimension,
        float[] vector,
        long createdAtMillis
) {
    public static final int SCHEMA_VERSION = 1;

    public AXEventVector {
        eventId = eventId == null ? "" : eventId.trim();
        eventFactHash = eventFactHash == null ? "" : eventFactHash.trim();
        embeddingModelName = embeddingModelName == null ? "" : embeddingModelName.trim();
        embeddingNamespace = embeddingNamespace == null || embeddingNamespace.isBlank() ? "unknown_embedding" : embeddingNamespace.trim();
        vector = vector == null ? new float[0] : vector.clone();
        dimension = dimension <= 0 ? vector.length : dimension;
        createdAtMillis = createdAtMillis <= 0L ? System.currentTimeMillis() : createdAtMillis;
    }

    public boolean isEmpty() {
        return eventId.isBlank() || embeddingNamespace.isBlank() || dimension <= 0 || vector.length == 0;
    }

    /**
     * 向量索引的<b>幂等键</b>：同一个事件的同一个事实哈希，在索引里只允许存在一条向量。
     *
     * <p><b>这是索引幂等，不是事件去重</b>。事件是客观事实记录，入库时<b>永不去重</b>——
     * 同一件事发生两次就是两条事件、两条向量，它们的 {@code eventId} 不同。这里挡的只有
     * "同一次写入被重放"：批次重试、维护补齐重跑、进程重启后续写，都会把同一条
     * {@code (eventId, eventFactHash)} 再送一遍，若不挡就会产生内容完全相同的重复向量，
     * 让召回里同一事件反复挤占候选。因此判据必须是"事件身份 + 事实内容"这一对键，
     * 而不是事实内容本身。</p>
     *
     * <p>键的格式只在本处定义：写入侧（{@code AXEventVectorStore}）与对账侧
     * （{@code AXMemoryMaintenanceService} 的向量补齐查询）必须用同一个函数，否则"哪些事件
     * 已经有向量"会被算错。</p>
     */
    public String idempotencyKey() {
        return idempotencyKey(eventId, eventFactHash);
    }

    /** 幂等键的事件侧重载：对账时从一个 {@code AXMemoryEvent} 的 {@code id} / {@code factHash} 取同一份键。 */
    public static String idempotencyKey(String eventId, String factHash) {
        return (eventId == null ? "" : eventId.trim()) + "\n" + (factHash == null ? "" : factHash.trim());
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("eventId", eventId);
        json.addProperty("eventFactHash", eventFactHash);
        json.addProperty("embeddingModelName", embeddingModelName);
        json.addProperty("embeddingNamespace", embeddingNamespace);
        json.addProperty("dimension", dimension);
        json.addProperty("createdAtMillis", createdAtMillis);
        JsonArray values = new JsonArray();
        for (float value : vector) {
            values.add(value);
        }
        json.add("vector", values);
        return json;
    }

    public static AXEventVector fromJson(JsonObject json) {
        if (json == null) {
            return new AXEventVector("", "", "", "", 0, new float[0], 0L);
        }
        return new AXEventVector(
                readString(json, "eventId"),
                readString(json, "eventFactHash"),
                readString(json, "embeddingModelName"),
                readString(json, "embeddingNamespace"),
                readInt(json, "dimension", 0),
                readFloatArray(json, "vector"),
                readLong(json, "createdAtMillis", 0L)
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

    private static float[] readFloatArray(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return new float[0];
        }
        JsonArray array = json.getAsJsonArray(key);
        float[] values = new float[array.size()];
        int index = 0;
        for (JsonElement element : array) {
            try {
                values[index++] = element == null || element.isJsonNull() ? 0.0F : element.getAsFloat();
            } catch (Exception e) {
                values[index - 1] = 0.0F;
            }
        }
        return values;
    }
}
