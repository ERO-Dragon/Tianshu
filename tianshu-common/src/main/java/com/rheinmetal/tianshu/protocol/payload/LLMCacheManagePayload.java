package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

public record LLMCacheManagePayload(
        String action,
        String uid,
        List<String> contents
) implements ITianshuPayload {

    public static final String ACTION_EVICT_ALL = "EVICT_ALL";
    public static final String ACTION_EVICT_CONTENT = "EVICT_CONTENT";
    public static final String ACTION_QUERY = "QUERY";

    public LLMCacheManagePayload {
        action = normalizeAction(action);
        uid = uid != null ? uid.trim() : "";
        contents = contents != null ? List.copyOf(contents) : List.of();
    }

    private static String normalizeAction(String value) {
        if (value == null || value.isBlank()) return ACTION_QUERY;
        String upper = value.trim().toUpperCase();
        return switch (upper) {
            case "EVICT_ALL", "EVICT_CONTENT", "QUERY" -> upper;
            default -> ACTION_QUERY;
        };
    }

    public static LLMCacheManagePayload evictAll(String uid) {
        return new LLMCacheManagePayload(ACTION_EVICT_ALL, uid, List.of());
    }

    public static LLMCacheManagePayload evictContent(String uid, List<String> contents) {
        return new LLMCacheManagePayload(ACTION_EVICT_CONTENT, uid, contents);
    }

    public static LLMCacheManagePayload query(String uid) {
        return new LLMCacheManagePayload(ACTION_QUERY, uid, List.of());
    }
}
