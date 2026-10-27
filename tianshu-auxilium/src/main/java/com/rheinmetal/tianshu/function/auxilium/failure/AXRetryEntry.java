package com.rheinmetal.tianshu.function.auxilium.failure;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXHashing;

/**
 * 一件没办成、还能再办一次的后台工作。
 *
 * <p>目前只有一种：<b>记忆维护</b>（把压缩窗口里的一批原文压成摘要块）。它值得记住，因为它是唯一
 * "失败了就白等一轮、而且可以原地重来一次"的后台工作——要压的那批原文还在压缩窗口里，重新触发一次就是全部。</p>
 *
 * <p>对话不在其中。一句没被回答的提问会从历史里撤掉（见 {@code AXRecentDialogueSystem#rollbackTo}），
 * 玩家想问就再问一次——那才是新的对话；给"重放上一问"一个按钮，等于让天枢替玩家决定该问什么。</p>
 */
public record AXRetryEntry(
        String id,
        AXFailureCause cause,
        String reasonCode,
        String worldId,
        long createdAtMillis,
        int attempts
) {
    public static final int SCHEMA_VERSION = 1;

    public AXRetryEntry {
        cause = cause == null ? AXFailureCause.UNKNOWN : cause;
        reasonCode = clean(reasonCode);
        worldId = clean(worldId);
        createdAtMillis = createdAtMillis <= 0L ? System.currentTimeMillis() : createdAtMillis;
        attempts = Math.max(0, attempts);
        id = clean(id).isBlank() ? "retry_" + AXHashing.sha256Short(worldId) : clean(id);
    }

    /** 一次失败的记忆维护：没有载荷，重新触发就是全部。 */
    public static AXRetryEntry memoryMaintenance(AXScope scope, AXFailureCause cause, String reasonCode, long nowMillis) {
        return new AXRetryEntry("", cause, reasonCode, scope == null ? "" : scope.worldId(), nowMillis, 0);
    }

    /**
     * 去重身份：一个世界同时只有一件没办成的维护，所以身份就是世界。
     *
     * <p>维护是一个世界内的单线程后台任务，同一时刻接不了两件；重复失败要替换记录，而不是攒成两行。</p>
     */
    public String identity() {
        return worldId;
    }

    /** 原因允许再来一次时才提供重试：参数错误、余额不足这类要改的是设置。 */
    public boolean retryable() {
        return cause.retryable();
    }

    /** 记一次重试：次数只为界面与诊断，不参与"能不能重试"的判定。 */
    public AXRetryEntry withAttempt() {
        return new AXRetryEntry(id, cause, reasonCode, worldId, createdAtMillis, attempts + 1);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("id", id);
        json.addProperty("cause", cause.name());
        json.addProperty("reasonCode", reasonCode);
        json.addProperty("worldId", worldId);
        json.addProperty("createdAtMillis", createdAtMillis);
        json.addProperty("attempts", attempts);
        return json;
    }

    public static AXRetryEntry fromJson(JsonObject json) {
        if (json == null || readString(json, "worldId").isBlank()) {
            return null;
        }
        return new AXRetryEntry(
                readString(json, "id"),
                AXFailureCause.fromCode(readString(json, "cause")),
                readString(json, "reasonCode"),
                readString(json, "worldId"),
                readLong(json, "createdAtMillis"),
                readInt(json, "attempts")
        );
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String readString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    private static long readLong(JsonObject json, String key) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsLong() : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static int readInt(JsonObject json, String key) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }
}