package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXHashing;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXTokenEstimator;

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
        String contentHash,
        String speakerName
) {
    public static final int SCHEMA_VERSION = 1;

    public AXRawTurn(
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
        this(id, role, content, createdAtMillis, worldId, iaSessionId, iaTurnId, estimatedTokens, characterCount, contentHash, "");
    }

    public AXRawTurn {
        role = normalizeRole(role);
        content = content == null ? "" : content.trim();
        speakerName = speakerName == null ? "" : speakerName.trim();
        createdAtMillis = createdAtMillis <= 0L ? System.currentTimeMillis() : createdAtMillis;
        worldId = worldId == null || worldId.isBlank() ? "unknown_world" : worldId.trim();
        iaSessionId = iaSessionId == null ? "" : iaSessionId.trim();
        iaTurnId = iaTurnId == null ? "" : iaTurnId.trim();
        characterCount = characterCount <= 0 ? content.length() : characterCount;
        estimatedTokens = estimatedTokens <= 0 ? new AXTokenEstimator().estimate(content) : estimatedTokens;
        contentHash = contentHash == null || contentHash.isBlank() ? AXHashing.sha256Short(role + "\n" + speakerName + "\n" + content) : contentHash.trim();
        id = id == null || id.isBlank()
                ? "raw_" + Long.toUnsignedString(createdAtMillis, 36) + "_" + AXHashing.sha256Short(worldId + "\n" + iaSessionId + "\n" + iaTurnId + "\n" + role + "\n" + speakerName + "\n" + content)
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
                "",
                ""
        );
    }

    public static AXRawTurn gameChat(AXScope scope, String speakerName, String content, long createdAtMillis, String sourceTurnId) {
        AXScope effectiveScope = scope == null ? AXScope.unknown() : scope;
        return new AXRawTurn(
                "",
                "game_chat",
                content,
                createdAtMillis,
                effectiveScope.worldId(),
                "",
                sourceTurnId,
                0,
                0,
                "",
                speakerName
        );
    }

    public boolean isEmpty() {
        return content.isBlank();
    }

    public boolean assistantRole() {
        return "assistant".equals(role);
    }

    public boolean chatRole() {
        return gameChatRole();
    }

    public boolean gameChatRole() {
        return "game_chat".equals(role);
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
        json.addProperty("speakerName", speakerName);
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
                readString(json, "contentHash"),
                readString(json, "speakerName")
        );
    }

    private static String normalizeRole(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return switch (normalized) {
            case "assistant", "ax" -> "assistant";
            case "system" -> "system";
            case "chat", "game_chat", "gamechat" -> "game_chat";
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
