package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

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
        int tokenCount,
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
            int tokenCount,
            int characterCount,
            String contentHash
    ) {
        this(id, role, content, createdAtMillis, worldId, iaSessionId, iaTurnId, tokenCount, characterCount, contentHash, "");
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
        tokenCount = Math.max(0, tokenCount);
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

    /**
     * An event-flow entry with a subject, and the external identity of its source.
     *
     * <p>{@code <event>} is a one-off fact appended to the history, not a tail block rebuilt every turn. Every
     * non-player source is one: another player's chat line, a message another mod delivered, and the world
     * changing on its own. The speaker therefore travels in {@code speakerName} and is written into the body by
     * the renderer — there is no second wrapper.
     *
     * @param speakerName  who said it, or empty when nobody did
     * @param sourceTurnId the sender's own identity for this line, so a re-delivery keeps the same turn id
     */
    public static AXRawTurn worldEvent(
            AXScope scope,
            String speakerName,
            String content,
            long createdAtMillis,
            String sourceTurnId
    ) {
        AXScope effectiveScope = scope == null ? AXScope.unknown() : scope;
        return new AXRawTurn(
                "",
                "world_event",
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

    /** An event from someone, with no external identity of its own. */
    public static AXRawTurn worldEvent(AXScope scope, String speakerName, String content, long createdAtMillis) {
        return worldEvent(scope, speakerName, content, createdAtMillis, "");
    }

    /** An event without a subject: the environment changed on its own. */
    public static AXRawTurn worldEvent(AXScope scope, String content, long createdAtMillis) {
        return worldEvent(scope, "", content, createdAtMillis, "");
    }

    public boolean isEmpty() {
        return content.isBlank();
    }

    public boolean assistantRole() {
        return "assistant".equals(role);
    }

    public boolean worldEventRole() {
        return "world_event".equals(role);
    }

    public AXRawTurn withTokenCount(int value) {
        return new AXRawTurn(id, role, content, createdAtMillis, worldId, iaSessionId, iaTurnId, value, characterCount, contentHash, speakerName);
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
        json.addProperty("tokenCount", tokenCount);
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
                readInt(json, "tokenCount", 0),
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
            // "chat" / "game_chat" are the retired second event flavour: history written by an older build still
            // reads as an event, and its speakerName keeps the subject visible.
            case "world_event", "chat", "game_chat", "gamechat" -> "world_event";
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
