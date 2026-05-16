package com.rheinmetal.tianshu.function.assistant.memory;

import com.google.gson.JsonObject;

import java.util.List;

public record ShortTermMemoryBlock(
        String id,
        long createdAt,
        long fromTurnCreatedAt,
        long toTurnCreatedAt,
        int sourceTurnCount,
        int sourceEstimatedTokens,
        String content
) {
    public ShortTermMemoryBlock {
        id = id == null ? "" : id.trim();
        createdAt = createdAt <= 0L ? System.currentTimeMillis() : createdAt;
        fromTurnCreatedAt = Math.max(0L, fromTurnCreatedAt);
        toTurnCreatedAt = Math.max(fromTurnCreatedAt, toTurnCreatedAt);
        sourceTurnCount = Math.max(0, sourceTurnCount);
        sourceEstimatedTokens = Math.max(0, sourceEstimatedTokens);
        content = content == null ? "" : content.trim();
    }

    public boolean isEmpty() {
        return id.isBlank() || content.isBlank();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("id", id);
        json.addProperty("createdAt", createdAt);
        json.addProperty("fromTurnCreatedAt", fromTurnCreatedAt);
        json.addProperty("toTurnCreatedAt", toTurnCreatedAt);
        json.addProperty("sourceTurnCount", sourceTurnCount);
        json.addProperty("sourceEstimatedTokens", sourceEstimatedTokens);
        json.addProperty("content", content);
        return json;
    }

    public static ShortTermMemoryBlock placeholder(String id, List<ConversationTurn> sourceTurns) {
        List<ConversationTurn> turns = sourceTurns == null ? List.of() : sourceTurns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .toList();
        int tokens = turns.stream().mapToInt(ConversationTurn::estimatedTokens).sum();
        long from = turns.isEmpty() ? 0L : turns.get(0).createdAt();
        long to = turns.isEmpty() ? from : turns.get(turns.size() - 1).createdAt();
        StringBuilder text = new StringBuilder();
        for (ConversationTurn turn : turns) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(turn.role()).append(": ").append(turn.content());
        }
        return new ShortTermMemoryBlock(id, System.currentTimeMillis(), from, to, turns.size(), tokens, text.toString());
    }

    public static ShortTermMemoryBlock fromJson(JsonObject json) {
        if (json == null) {
            return new ShortTermMemoryBlock("", 0L, 0L, 0L, 0, 0, "");
        }
        return new ShortTermMemoryBlock(
                readString(json, "id"),
                readLong(json, "createdAt", System.currentTimeMillis()),
                readLong(json, "fromTurnCreatedAt", 0L),
                readLong(json, "toTurnCreatedAt", 0L),
                readInt(json, "sourceTurnCount", 0),
                readInt(json, "sourceEstimatedTokens", 0),
                readString(json, "content")
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
}
