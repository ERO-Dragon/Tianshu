package com.rheinmetal.tianshu.function.auxilium.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.stream.Collectors;

public record AXCompressionTask(
        String taskId,
        AXCompressionTaskType type,
        AXCompressionTaskState state,
        long createdAt,
        long updatedAt,
        int sourceTurnCount,
        int estimatedTokens,
        boolean pauseBoundary,
        boolean forcedByMaxWindow,
        List<ConversationTurn> sourceTurns,
        String resultText,
        String errorCode,
        String errorMessage,
        int retryCount,
        long nextAttemptAt
) {
    public AXCompressionTask {
        taskId = taskId == null ? "" : taskId.trim();
        type = type == null ? AXCompressionTaskType.SHORT_TERM_MEMORY : type;
        state = state == null ? AXCompressionTaskState.PLANNED : state;
        createdAt = createdAt <= 0L ? System.currentTimeMillis() : createdAt;
        updatedAt = updatedAt <= 0L ? createdAt : updatedAt;
        sourceTurns = sourceTurns == null ? List.of() : sourceTurns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .toList();
        sourceTurnCount = sourceTurnCount <= 0 ? sourceTurns.size() : sourceTurnCount;
        estimatedTokens = estimatedTokens <= 0 ? sourceTurns.stream().mapToInt(ConversationTurn::estimatedTokens).sum() : estimatedTokens;
        resultText = resultText == null ? "" : resultText.trim();
        errorCode = errorCode == null ? "" : errorCode.trim();
        errorMessage = errorMessage == null ? "" : errorMessage.trim();
        retryCount = Math.max(0, retryCount);
        nextAttemptAt = Math.max(0L, nextAttemptAt);
    }

    public boolean isEmpty() {
        return taskId.isBlank() || sourceTurns.isEmpty();
    }

    public boolean terminal() {
        return state == AXCompressionTaskState.COMPLETED
                || state == AXCompressionTaskState.FAILED
                || state == AXCompressionTaskState.CANCELLED;
    }

    public boolean readyToAttempt(long now) {
        return !terminal() && (state == AXCompressionTaskState.PLANNED || state == AXCompressionTaskState.SUSPENDED || state == AXCompressionTaskState.FAILED) && nextAttemptAt <= Math.max(0L, now);
    }

    public AXCompressionTask transition(AXCompressionTaskState nextState) {
        return new AXCompressionTask(taskId, type, nextState, createdAt, System.currentTimeMillis(), sourceTurnCount, estimatedTokens, pauseBoundary, forcedByMaxWindow, sourceTurns, resultText, errorCode, errorMessage, retryCount, nextAttemptAt);
    }

    public AXCompressionTask submitted() {
        return new AXCompressionTask(taskId, type, AXCompressionTaskState.SUBMITTED, createdAt, System.currentTimeMillis(), sourceTurnCount, estimatedTokens, pauseBoundary, forcedByMaxWindow, sourceTurns, resultText, "", "", retryCount, 0L);
    }

    public AXCompressionTask suspended(String code, String message, long nextAttemptAt) {
        return new AXCompressionTask(taskId, type, AXCompressionTaskState.SUSPENDED, createdAt, System.currentTimeMillis(), sourceTurnCount, estimatedTokens, pauseBoundary, forcedByMaxWindow, sourceTurns, resultText, code, message, retryCount + 1, nextAttemptAt);
    }

    public AXCompressionTask complete(String text) {
        return new AXCompressionTask(taskId, type, AXCompressionTaskState.COMPLETED, createdAt, System.currentTimeMillis(), sourceTurnCount, estimatedTokens, pauseBoundary, forcedByMaxWindow, sourceTurns, text, "", "", retryCount, 0L);
    }

    public AXCompressionTask fail(String code, String message) {
        return new AXCompressionTask(taskId, type, AXCompressionTaskState.FAILED, createdAt, System.currentTimeMillis(), sourceTurnCount, estimatedTokens, pauseBoundary, forcedByMaxWindow, sourceTurns, resultText, code, message, retryCount + 1, nextAttemptAt);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("taskId", taskId);
        json.addProperty("type", type.name());
        json.addProperty("state", state.name());
        json.addProperty("createdAt", createdAt);
        json.addProperty("updatedAt", updatedAt);
        json.addProperty("sourceTurnCount", sourceTurnCount);
        json.addProperty("estimatedTokens", estimatedTokens);
        json.addProperty("pauseBoundary", pauseBoundary);
        json.addProperty("forcedByMaxWindow", forcedByMaxWindow);
        JsonArray turns = new JsonArray();
        sourceTurns.forEach(turn -> turns.add(turn.toJson()));
        json.add("sourceTurns", turns);
        json.addProperty("resultText", resultText);
        json.addProperty("errorCode", errorCode);
        json.addProperty("errorMessage", errorMessage);
        json.addProperty("retryCount", retryCount);
        json.addProperty("nextAttemptAt", nextAttemptAt);
        return json;
    }

    public static AXCompressionTask fromJson(JsonObject json) {
        if (json == null) {
            return empty();
        }
        JsonArray source = json.has("sourceTurns") && json.get("sourceTurns").isJsonArray() ? json.getAsJsonArray("sourceTurns") : new JsonArray();
        List<ConversationTurn> turns = java.util.stream.StreamSupport.stream(source.spliterator(), false)
                .filter(element -> element != null && element.isJsonObject())
                .map(element -> ConversationTurn.fromJson(element.getAsJsonObject()))
                .filter(turn -> !turn.isEmpty())
                .toList();
        return new AXCompressionTask(
                readString(json, "taskId"),
                readType(json),
                readState(json),
                readLong(json, "createdAt", System.currentTimeMillis()),
                readLong(json, "updatedAt", System.currentTimeMillis()),
                readInt(json, "sourceTurnCount", turns.size()),
                readInt(json, "estimatedTokens", 0),
                readBoolean(json, "pauseBoundary"),
                readBoolean(json, "forcedByMaxWindow"),
                turns,
                readString(json, "resultText"),
                readString(json, "errorCode"),
                readString(json, "errorMessage"),
                readInt(json, "retryCount", 0),
                readLong(json, "nextAttemptAt", 0L)
        );
    }

    public static AXCompressionTask fromCandidate(String taskId, ShortTermCompressionCandidate candidate) {
        if (candidate == null) {
            return empty();
        }
        return new AXCompressionTask(taskId, AXCompressionTaskType.SHORT_TERM_MEMORY, AXCompressionTaskState.PLANNED, System.currentTimeMillis(), System.currentTimeMillis(), candidate.turns().size(), candidate.estimatedTokens(), candidate.pauseBoundary(), candidate.forcedByMaxWindow(), candidate.turns(), "", "", "", 0, 0L);
    }

    public static AXCompressionTask fromLongTermBlocks(String taskId, List<ShortTermMemoryBlock> blocks) {
        List<ShortTermMemoryBlock> normalized = blocks == null ? List.of() : blocks.stream()
                .filter(block -> block != null && !block.isEmpty())
                .toList();
        String content = normalized.stream()
                .map(ShortTermMemoryBlock::content)
                .collect(Collectors.joining("\n"));
        ConversationTurn synthetic = new ConversationTurn("user", content, System.currentTimeMillis());
        int tokens = normalized.stream().mapToInt(ShortTermMemoryBlock::sourceEstimatedTokens).sum();
        int sourceBlockCount = normalized.size();
        return new AXCompressionTask(taskId, AXCompressionTaskType.LONG_TERM_MEMORY, AXCompressionTaskState.PLANNED, System.currentTimeMillis(), System.currentTimeMillis(), sourceBlockCount, tokens, false, false, List.of(synthetic), "", "", "", 0, 0L);
    }

    public static AXCompressionTask empty() {
        return new AXCompressionTask("", AXCompressionTaskType.SHORT_TERM_MEMORY, AXCompressionTaskState.PLANNED, 0L, 0L, 0, 0, false, false, List.of(), "", "", "", 0, 0L);
    }

    private static AXCompressionTaskType readType(JsonObject json) {
        try {
            return AXCompressionTaskType.valueOf(readString(json, "type"));
        } catch (Exception e) {
            return AXCompressionTaskType.SHORT_TERM_MEMORY;
        }
    }

    private static AXCompressionTaskState readState(JsonObject json) {
        try {
            return AXCompressionTaskState.valueOf(readString(json, "state"));
        } catch (Exception e) {
            return AXCompressionTaskState.PLANNED;
        }
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

    private static boolean readBoolean(JsonObject json, String key) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() && json.get(key).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }
}
