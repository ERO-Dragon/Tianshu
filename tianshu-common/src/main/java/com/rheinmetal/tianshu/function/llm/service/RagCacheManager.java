package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.libs.rag.RagSearchResult;

import java.util.List;

public interface RagCacheManager {

    void upsert(String uid, String entryId, String content, float[] vector);

    void patch(String uid, String entryId, String content, float[] vector, boolean updateContent, boolean updateVector);

    void deleteEntry(String uid, String entryId);

    void clearUid(String uid);

    boolean hasEntry(String uid, String entryId);

    List<RagEntrySearchResult> searchEntries(String uid, String queryText, int topK, float threshold);

    List<RagSearchResult> search(String uid, String queryText, int topK, float threshold);

    boolean hasCache(String uid);

    CacheStats getStats();

    void clear();

    record RagEntrySearchResult(String entryId, String content, double score) {
        public RagEntrySearchResult {
            entryId = entryId == null ? "" : entryId.trim();
            content = content == null ? "" : content;
            score = Math.max(0.0D, score);
        }
    }
}
