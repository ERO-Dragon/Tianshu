package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

public record LLMCacheManagePayload(
        String action,
        String uid,
        String modid,
        String visibility,
        List<String> tags,
        String entryId,
        String content,
        float[] vector,
        Boolean updateContent,
        Boolean updateVector,
        String queryText,
        Integer topK,
        Float threshold
) implements ITianshuPayload {

    public static final String ACTION_UPSERT_ENTRY = "UPSERT_ENTRY";
    public static final String ACTION_PATCH_ENTRY = "PATCH_ENTRY";
    public static final String ACTION_DELETE_ENTRY = "DELETE_ENTRY";
    public static final String ACTION_CLEAR_UID = "CLEAR_UID";
    public static final String ACTION_QUERY_UID = "QUERY_UID";
    public static final String ACTION_REGISTER_LIBRARY = "REGISTER_LIBRARY";
    public static final String ACTION_UNREGISTER_LIBRARY = "UNREGISTER_LIBRARY";
    public static final String ACTION_SEARCH_UID = "SEARCH_UID";
    public static final String ACTION_SEARCH_MODID = "SEARCH_MODID";
    public static final String ACTION_SEARCH_TAGS = "SEARCH_TAGS";

    public LLMCacheManagePayload {
        action = normalizeAction(action);
        uid = clean(uid);
        modid = clean(modid).toLowerCase();
        visibility = normalizeVisibility(visibility);
        tags = normalizeTags(tags);
        entryId = clean(entryId);
        content = content == null ? "" : content.trim();
        vector = vector == null ? new float[0] : vector.clone();
        updateContent = updateContent != null ? updateContent : false;
        updateVector = updateVector != null ? updateVector : false;
        queryText = queryText == null ? "" : queryText.trim();
        topK = topK != null && topK > 0 ? topK : 4;
        threshold = threshold != null && threshold > 0f && threshold <= 1f ? threshold : 0.7f;
    }

    public static LLMCacheManagePayload upsertEntry(String uid, String entryId, String content, float[] vector) {
        return new LLMCacheManagePayload(ACTION_UPSERT_ENTRY, uid, "", "SHARED", List.of(), entryId, content, vector, true, true, "", 4, 0.7f);
    }

    public static LLMCacheManagePayload patchEntry(String uid, String entryId, String content, float[] vector, boolean updateContent, boolean updateVector) {
        return new LLMCacheManagePayload(ACTION_PATCH_ENTRY, uid, "", "SHARED", List.of(), entryId, content, vector, updateContent, updateVector, "", 4, 0.7f);
    }

    public static LLMCacheManagePayload deleteEntry(String uid, String entryId) {
        return new LLMCacheManagePayload(ACTION_DELETE_ENTRY, uid, "", "SHARED", List.of(), entryId, "", null, false, false, "", 4, 0.7f);
    }

    public static LLMCacheManagePayload clearUid(String uid) {
        return new LLMCacheManagePayload(ACTION_CLEAR_UID, uid, "", "SHARED", List.of(), "", "", null, false, false, "", 4, 0.7f);
    }

    public static LLMCacheManagePayload queryUid(String uid) {
        return new LLMCacheManagePayload(ACTION_QUERY_UID, uid, "", "SHARED", List.of(), "", "", null, false, false, "", 4, 0.7f);
    }

    public static LLMCacheManagePayload registerLibrary(String uid, String modid, String visibility, List<String> tags) {
        return new LLMCacheManagePayload(ACTION_REGISTER_LIBRARY, uid, modid, visibility, tags, "", "", null, false, false, "", 4, 0.7f);
    }

    public static LLMCacheManagePayload unregisterLibrary(String uid) {
        return new LLMCacheManagePayload(ACTION_UNREGISTER_LIBRARY, uid, "", "SHARED", List.of(), "", "", null, false, false, "", 4, 0.7f);
    }

    public static LLMCacheManagePayload searchUid(String uid, String queryText, int topK, float threshold) {
        return new LLMCacheManagePayload(ACTION_SEARCH_UID, uid, "", "SHARED", List.of(), "", "", null, false, false, queryText, topK, threshold);
    }

    public static LLMCacheManagePayload searchModid(String modid, String queryText, int topK, float threshold) {
        return new LLMCacheManagePayload(ACTION_SEARCH_MODID, "", modid, "SHARED", List.of(), "", "", null, false, false, queryText, topK, threshold);
    }

    public static LLMCacheManagePayload searchTags(List<String> tags, String queryText, int topK, float threshold) {
        return new LLMCacheManagePayload(ACTION_SEARCH_TAGS, "", "", "SHARED", tags, "", "", null, false, false, queryText, topK, threshold);
    }

    private static String normalizeAction(String value) {
        if (value == null || value.isBlank()) {
            return ACTION_QUERY_UID;
        }
        String upper = value.trim().toUpperCase();
        return switch (upper) {
            case ACTION_UPSERT_ENTRY, ACTION_PATCH_ENTRY, ACTION_DELETE_ENTRY, ACTION_CLEAR_UID, ACTION_QUERY_UID,
                    ACTION_REGISTER_LIBRARY, ACTION_UNREGISTER_LIBRARY, ACTION_SEARCH_UID, ACTION_SEARCH_MODID, ACTION_SEARCH_TAGS -> upper;
            default -> upper;
        };
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeVisibility(String value) {
        String normalized = clean(value).toUpperCase();
        return "PRIVATE".equals(normalized) ? "PRIVATE" : "SHARED";
    }

    private static List<String> normalizeTags(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase())
                .distinct()
                .toList();
    }
}
