package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Persistent RAG storage and library registry independent from generation runtime. */
public final class LlmRagStorageService {
    private final SwitchableEmbeddingService embeddingService = new SwitchableEmbeddingService();
    private final RagCacheManager cache;
    private final RagLibraryRegistry libraries;

    public LlmRagStorageService(
            IGameEnvironment environment,
            Path cacheDirectory,
            String cacheNamespace,
            boolean persistent,
            Executor searchExecutor,
            RagPersistenceScheduler persistenceScheduler
    ) {
        Objects.requireNonNull(environment, "environment");
        this.cache = persistent
                ? new PersistentRagCacheManager(environment, embeddingService, cacheDirectory, cacheNamespace, searchExecutor, persistenceScheduler)
                : new DefaultRagCacheManager(environment, embeddingService, searchExecutor);
        this.libraries = new RagLibraryRegistry(environment, persistent ? cacheDirectory : null);
    }

    public void bindEmbeddingService(EmbeddingService service) {
        embeddingService.bind(service);
    }

    public boolean embeddingAvailable() {
        return embeddingService.available();
    }

    public RagCacheManager cache() {
        return cache;
    }

    public float[] embedQueryVector(String queryText) {
        try {
            return embeddingService.embed(queryText);
        } catch (Exception ignored) {
            return null;
        }
    }

    public RagWriteResult upsert(String uid, String entryId, String content, float[] vector) {
        if (requiresEmbedding(content, vector) && !embeddingAvailable()) {
            return RagWriteResult.failure("EMBEDDING_NOT_READY");
        }
        cache.upsert(uid, entryId, content, vector);
        return cache.hasEntry(uid, entryId)
                ? RagWriteResult.ok()
                : RagWriteResult.failure("RAG_WRITE_FAILED");
    }

    public RagWriteResult patch(String uid, String entryId, String content, float[] vector, boolean updateContent, boolean updateVector) {
        if (updateVector && requiresEmbedding(content, vector) && !embeddingAvailable()) {
            return RagWriteResult.failure("EMBEDDING_NOT_READY");
        }
        cache.patch(uid, entryId, content, vector, updateContent, updateVector);
        return cache.hasEntry(uid, entryId)
                ? RagWriteResult.ok()
                : RagWriteResult.failure("RAG_WRITE_FAILED");
    }

    public boolean delete(String uid, String entryId) {
        cache.deleteEntry(uid, entryId);
        return !cache.hasEntry(uid, entryId);
    }

    public boolean clear(String uid) {
        cache.clearUid(uid);
        return !cache.hasCache(uid);
    }

    public boolean hasEntry(String uid, String entryId) {
        return cache.hasEntry(uid, entryId);
    }

    public boolean hasCache(String uid) {
        return cache.hasCache(uid);
    }

    public List<RagCacheManager.RagEntrySearchResult> searchEntries(String uid, String queryText, int topK, float threshold) {
        return cache.searchEntries(uid, queryText, topK, threshold);
    }

    public List<RagCacheManager.RagEntrySearchResult> searchEntries(String uid, String queryText, float[] queryVector, int topK, float threshold) {
        return cache.searchEntries(uid, queryText, queryVector, topK, threshold);
    }

    public List<RagCacheManager.RagEntrySearchResult> searchInline(List<String> contents, String queryText, int topK, float threshold) {
        if (contents == null || contents.isEmpty() || queryText == null || queryText.isBlank()) {
            return List.of();
        }
        VectorStore store = new VectorStore("inline");
        int index = 0;
        for (String content : contents) {
            if (content != null && !content.isBlank()) {
                store.upsert(Integer.toString(index), content, null);
            }
            index++;
        }
        return store.searchEntries(null, store.bm25Scores(queryText), topK, threshold);
    }

    public RagLibraryRegistry.RagLibraryMeta registerLibrary(String uid, String modid, String visibility, List<String> tags) {
        libraries.register(uid, modid, visibility, tags);
        return libraries.meta(uid);
    }

    public void unregisterLibrary(String uid) {
        libraries.unregister(uid);
    }

    public RagLibraryRegistry.RagLibraryMeta library(String uid) {
        return libraries.meta(uid);
    }

    public List<RagLibraryRegistry.RagLibraryMeta> sharedByModid(String modid) {
        return libraries.sharedByModid(modid);
    }

    public List<RagLibraryRegistry.RagLibraryMeta> sharedByTags(List<String> tags) {
        return libraries.sharedByTags(tags);
    }

    public List<LibrarySearchResult> searchSharedByModid(String modid, String queryText, int topK, float threshold) {
        return searchLibraries(libraries.sharedByModid(modid), queryText, topK, threshold);
    }

    public List<LibrarySearchResult> searchSharedByTags(List<String> tags, String queryText, int topK, float threshold) {
        return searchLibraries(libraries.sharedByTags(tags), queryText, topK, threshold);
    }

    public void shutdown() {
        cache.flush();
    }

    private List<LibrarySearchResult> searchLibraries(
            List<RagLibraryRegistry.RagLibraryMeta> candidates,
            String queryText,
            int topK,
            float threshold
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<LibrarySearchResult> results = new java.util.ArrayList<>();
        for (RagLibraryRegistry.RagLibraryMeta library : candidates) {
            List<RagCacheManager.RagEntrySearchResult> entries = searchEntries(library.uid(), queryText, topK, threshold);
            if (!entries.isEmpty()) {
                results.add(new LibrarySearchResult(library.uid(), library, entries));
            }
        }
        return List.copyOf(results);
    }

    private boolean requiresEmbedding(String content, float[] vector) {
        return !hasUsableVector(vector) && content != null && !content.isBlank();
    }

    private static boolean hasUsableVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            return false;
        }
        for (float value : vector) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                return false;
            }
        }
        return true;
    }

    public record RagWriteResult(boolean success, String errorCode) {
        public RagWriteResult {
            errorCode = errorCode == null ? "" : errorCode.trim();
        }

        static RagWriteResult ok() {
            return new RagWriteResult(true, "");
        }

        static RagWriteResult failure(String errorCode) {
            return new RagWriteResult(false, errorCode);
        }
    }

    public record LibrarySearchResult(
            String uid,
            RagLibraryRegistry.RagLibraryMeta library,
            List<RagCacheManager.RagEntrySearchResult> entries
    ) {
        public LibrarySearchResult {
            uid = uid == null ? "" : uid.trim();
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    private static final class SwitchableEmbeddingService implements EmbeddingService {
        private volatile EmbeddingService delegate = new UnavailableEmbeddingService();

        void bind(EmbeddingService service) {
            delegate = service == null ? new UnavailableEmbeddingService() : service;
        }

        boolean available() {
            return !(delegate instanceof UnavailableEmbeddingService);
        }

        @Override
        public float[] embed(String text) throws Exception {
            return delegate.embed(text);
        }

        @Override
        public float[][] embed(List<String> texts) throws Exception {
            return delegate.embed(texts);
        }

        @Override
        public int getEmbeddingDimension() {
            return delegate.getEmbeddingDimension();
        }
    }

    private static final class UnavailableEmbeddingService implements EmbeddingService {
        @Override
        public float[] embed(String text) {
            throw new IllegalStateException("EMBEDDING_NOT_READY");
        }

        @Override
        public float[][] embed(List<String> texts) {
            throw new IllegalStateException("EMBEDDING_NOT_READY");
        }

        @Override
        public int getEmbeddingDimension() {
            return 0;
        }
    }
}
