package com.rheinmetal.tianshu.function.auxilium.memory;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXHashing;

public record AXRawTurn(
        String id,
        String role,
        String content,
        long createdAtMillis,
        String worldId,
        String iaSessionId,
        String iaTurnId,
        int estimatedTokens,
        int characterCount,
        String contentHash
) {
    public static final int SCHEMA_VERSION = 1;

    public AXRawTurn {
        role = normalizeRole(role);
        content = content == null ? "" : content.trim();
        createdAtMillis = createdAtMillis <= 0L ? System.currentTimeMillis() : createdAtMillis;
        worldId = worldId == null || worldId.isBlank() ? "unknown_world" : worldId.trim();
        iaSessionId = iaSessionId == null ? "" : iaSessionId.trim();
        iaTurnId = iaTurnId == null ? "" : iaTurnId.trim();
        characterCount = characterCount <= 0 ? content.length() : characterCount;
        estimatedTokens = estimatedTokens <= 0 ? new AXTokenEstimator().estimate(content) : estimatedTokens;
        contentHash = contentHash == null || contentHash.isBlank() ? AXHashing.sha256Short(role + "\n" + content) : contentHash.trim();
        id = id == null || id.isBlank()
                ? "raw_" + Long.toUnsignedString(createdAtMillis, 36) + "_" + AXHashing.sha256Short(worldId + "\n" + iaSessionId + "\n" + iaTurnId + "\n" + role + "\n" + content)
                : id.trim();
    }

    public static AXRawTurn dialogue(AXScope scope, String role, String content, String iaSessionId, String iaTurnId) {
        AXScope effectiveScope = scope == null ? AXScope.unknown() : scope;
        return new AXRawTurn(
                "",
                role,
                content,
                System.currentTimeMillis(),
                effectiveScope.worldId(),
                iaSessionId,
                iaTurnId,
                0,
                0,
                ""
        );
    }

    public boolean isEmpty() {
        return content.isBlank();
    }

    public boolean assistantRole() {
        return "assistant".equals(role);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("id", id);
        json.addProperty("role", role);
        json.addProperty("content", content);
        json.addProperty("createdAtMillis", createdAtMillis);
        json.addProperty("worldId", worldId);
        json.addProperty("iaSessionId", iaSessionId);
        json.addProperty("iaTurnId", iaTurnId);
        json.addProperty("estimatedTokens", estimatedTokens);
        json.addProperty("characterCount", characterCount);
        json.addProperty("contentHash", contentHash);
        return json;
    }

    public static AXRawTurn fromJson(JsonObject json) {
        if (json == null) {
            return new AXRawTurn("", "user", "", 0L, "", "", "", 0, 0, "");
        }
        return new AXRawTurn(
                readString(json, "id"),
                readString(json, "role"),
                readString(json, "content"),
                readLong(json, "createdAtMillis", readLong(json, "createdAt", 0L)),
                readString(json, "worldId"),
                readString(json, "iaSessionId"),
                readString(json, "iaTurnId"),
                readInt(json, "estimatedTokens", 0),
                readInt(json, "characterCount", 0),
                readString(json, "contentHash")
        );
    }

    private static String normalizeRole(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return switch (normalized) {
            case "assistant", "ax" -> "assistant";
            case "system" -> "system";
            case "world_event" -> "world_event";
            default -> "user";
        };
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
}
