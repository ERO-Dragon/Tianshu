package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

public record LLMCacheManagePayload(
        String action,
        String uid,
        List<String> contents,
        Boolean globalRagCache
) implements ITianshuPayload {

    public static final String ACTION_INDEX = "INDEX";
    public static final String ACTION_EVICT_ALL = "EVICT_ALL";
    public static final String ACTION_EVICT_CONTENT = "EVICT_CONTENT";
    public static final String ACTION_QUERY = "QUERY";

    public LLMCacheManagePayload {
        action = normalizeAction(action);
        uid = uid != null ? uid.trim() : "";
        contents = contents != null ? List.copyOf(contents) : List.of();
        globalRagCache = globalRagCache != null ? globalRagCache : false;
    }

    public LLMCacheManagePayload(String action, String uid, List<String> contents) {
        this(action, uid, contents, false);
    }

    private static String normalizeAction(String value) {
        if (value == null || value.isBlank()) return ACTION_QUERY;
        String upper = value.trim().toUpperCase();
        return switch (upper) {
            case "INDEX", "EVICT_ALL", "EVICT_CONTENT", "QUERY" -> upper;
            default -> ACTION_QUERY;
        };
    }

    public static LLMCacheManagePayload index(String uid, List<String> contents) {
        return new LLMCacheManagePayload(ACTION_INDEX, uid, contents, false);
    }

    public static LLMCacheManagePayload indexGlobal(String uid, List<String> contents) {
        return new LLMCacheManagePayload(ACTION_INDEX, uid, contents, true);
    }

    public static LLMCacheManagePayload evictAll(String uid) {
        return new LLMCacheManagePayload(ACTION_EVICT_ALL, uid, List.of(), false);
    }

    public static LLMCacheManagePayload evictAllGlobal(String uid) {
        return new LLMCacheManagePayload(ACTION_EVICT_ALL, uid, List.of(), true);
    }

    public static LLMCacheManagePayload evictContent(String uid, List<String> contents) {
        return new LLMCacheManagePayload(ACTION_EVICT_CONTENT, uid, contents, false);
    }

    public static LLMCacheManagePayload evictGlobalContent(String uid, List<String> contents) {
        return new LLMCacheManagePayload(ACTION_EVICT_CONTENT, uid, contents, true);
    }

    public static LLMCacheManagePayload query(String uid) {
        return new LLMCacheManagePayload(ACTION_QUERY, uid, List.of(), false);
    }

    public static LLMCacheManagePayload queryGlobal(String uid) {
        return new LLMCacheManagePayload(ACTION_QUERY, uid, List.of(), true);
    }
}
