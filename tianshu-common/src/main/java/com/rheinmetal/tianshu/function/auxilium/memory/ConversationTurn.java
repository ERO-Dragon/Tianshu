package com.rheinmetal.tianshu.function.auxilium.memory;

import com.google.gson.JsonObject;

public record ConversationTurn(
        String role,
        String content,
        long createdAt,
        int estimatedTokens,
        int characterCount
) {
    public ConversationTurn(String role, String content, long createdAt) {
        this(role, content, createdAt, new AXTokenEstimator().estimate(content), content == null ? 0 : content.length());
    }

    public ConversationTurn {
        role = normalizeRole(role);
        content = content == null ? "" : content.trim();
        createdAt = createdAt <= 0L ? System.currentTimeMillis() : createdAt;
        characterCount = characterCount <= 0 ? content.length() : characterCount;
        estimatedTokens = estimatedTokens <= 0 ? new AXTokenEstimator().estimate(content) : estimatedTokens;
    }

    public boolean isEmpty() {
        return content.isBlank();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 2);
        json.addProperty("role", role);
        json.addProperty("content", content);
        json.addProperty("createdAt", createdAt);
        json.addProperty("estimatedTokens", estimatedTokens);
        json.addProperty("characterCount", characterCount);
        return json;
    }

    public static ConversationTurn fromJson(JsonObject json) {
        if (json == null) {
            return new ConversationTurn("user", "", System.currentTimeMillis());
        }
        String role = readString(json, "role", "user");
        String content = readString(json, "content", "");
        long createdAt = readLong(json, "createdAt", System.currentTimeMillis());
        int estimatedTokens = readInt(json, "estimatedTokens", 0);
        int characterCount = readInt(json, "characterCount", 0);
        return new ConversationTurn(role, content, createdAt, estimatedTokens, characterCount);
    }

    private static String normalizeRole(String value) {
        if ("AX".equals(value) || "system".equals(value)) {
            return value;
        }
        return "user";
    }

    private static String readString(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
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
}
