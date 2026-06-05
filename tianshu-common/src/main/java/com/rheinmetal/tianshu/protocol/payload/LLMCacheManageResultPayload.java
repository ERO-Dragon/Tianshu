package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record LLMCacheManageResultPayload(
        String action,
        String uid,
        boolean success,
        boolean exists,
        String errorMessage
) implements ITianshuPayload {

    public LLMCacheManageResultPayload {
        action = action != null ? action.trim().toUpperCase() : "";
        uid = uid != null ? uid.trim() : "";
        errorMessage = errorMessage != null && !errorMessage.isBlank() ? errorMessage.trim() : null;
    }

    public static LLMCacheManageResultPayload evicted(String uid, boolean success) {
        return new LLMCacheManageResultPayload("EVICT", uid, success, true, null);
    }

    public static LLMCacheManageResultPayload queried(String uid, boolean exists) {
        return new LLMCacheManageResultPayload("QUERY", uid, true, exists, null);
    }

    public static LLMCacheManageResultPayload failed(String uid, String errorMessage) {
        return new LLMCacheManageResultPayload("FAILED", uid, false, false, errorMessage);
    }
}
