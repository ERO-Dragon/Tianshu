package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.libs.rag.RagSearchResult;

import java.util.List;

public interface RagCacheManager {

    void index(String uid, List<String> texts);

    List<RagSearchResult> search(String uid, String queryText, int topK, float threshold);

    void evict(String uid);

    void evict(String uid, String content);

    boolean hasCache(String uid);

    CacheStats getStats();

    void clear();
}
