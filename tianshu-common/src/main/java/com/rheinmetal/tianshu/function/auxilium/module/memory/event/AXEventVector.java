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
