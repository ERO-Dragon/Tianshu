package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

final class LlmRagService {
    private static final int DEFAULT_TOP_K = 4;
    private static final float DEFAULT_THRESHOLD = 0.7F;

    private final IGameEnvironment environment;
    private final LlmInferenceClient inferenceClient;
    private final LlmRagStorageService storage;

    LlmRagService(
            IGameEnvironment environment,
            LlmInferenceClient inferenceClient,
            EmbeddingService embeddingService,
            boolean persistent,
            Path cacheDirectory,
            String cacheNamespace,
            Executor searchExecutor,
            RagPersistenceScheduler persistenceScheduler
    ) {
        this.environment = environment;
        this.inferenceClient = inferenceClient;
        this.storage = new LlmRagStorageService(
                environment,
                cacheDirectory,
                cacheNamespace,
                persistent,
                searchExecutor,
                persistenceScheduler
        );
        this.storage.bindEmbeddingService(embeddingService);
    }

    LlmRagService(IGameEnvironment environment, LlmInferenceClient inferenceClient, LlmRagStorageService storage) {
        this.environment = environment;
        this.inferenceClient = inferenceClient;
        this.storage = storage;
    }

    RagPreparation prepareChunk(Chunk chunk, String queryText) {
        if (chunk == null || queryText == null || queryText.isBlank()) {
            return RagPreparation.empty();
        }
        List<RagCacheManager.RagEntrySearchResult> results = searchChunk(chunk, queryText);
        List<RagCacheManager.RagEntrySearchResult> budgeted = applyTokenBudget(results, chunk.getMemoryRagTokenBudget());
        return new RagPreparation(budgeted, buildPrompt(budgeted, chunk.getPrompt()));
    }

    List<LLMPromptResultPayload.RagHitPayload> hits(Chunk chunk, List<RagCacheManager.RagEntrySearchResult> results) {
        if (chunk == null || !Boolean.TRUE.equals(chunk.getIncludeRagHits()) || results == null || results.isEmpty()) {
            return List.of();
        }
        List<LLMPromptResultPayload.HitEntry> entries = results.stream()
                .map(result -> LLMPromptResultPayload.HitEntry.of(result.entryId(), result.content(), result.score()))
                .toList();
        return List.of(LLMPromptResultPayload.RagHitPayload.of(chunk.getUid(), entries));
    }

    RagCacheManager cache() {
        return storage.cache();
    }

    boolean hasCache(String uid) {
        return storage.hasCache(uid);
    }

    void upsert(String uid, String entryId, String content, float[] vector) {
        storage.upsert(uid, entryId, content, vector);
    }

    void patch(String uid, String entryId, String content, float[] vector, boolean updateContent, boolean updateVector) {
        storage.patch(uid, entryId, content, vector, updateContent, updateVector);
    }

    void delete(String uid, String entryId) {
        storage.delete(uid, entryId);
    }

    void clear(String uid) {
        storage.clear(uid);
    }

    boolean hasEntry(String uid, String entryId) {
        return storage.hasEntry(uid, entryId);
    }

    List<RagCacheManager.RagEntrySearchResult> searchEntries(String uid, String queryText, int topK, float threshold) {
        return storage.searchEntries(uid, queryText, topK, threshold);
    }

    List<RagCacheManager.RagEntrySearchResult> searchEntries(String uid, String queryText, float[] queryVector, int topK, float threshold) {
        return storage.searchEntries(uid, queryText, queryVector, topK, threshold);
    }

    RagLibraryRegistry.RagLibraryMeta registerLibrary(String uid, String modid, String visibility, List<String> tags) {
        return storage.registerLibrary(uid, modid, visibility, tags);
    }

    void unregisterLibrary(String uid) {
        storage.unregisterLibrary(uid);
    }

    RagLibraryRegistry.RagLibraryMeta library(String uid) {
        return storage.library(uid);
    }

    List<LibrarySearchResult> searchLibraryByUid(String uid, String queryText, int topK, float threshold) {
        List<RagCacheManager.RagEntrySearchResult> entries = searchEntries(uid, queryText, topK, threshold);
        return entries.isEmpty()
                ? List.of()
                : List.of(new LibrarySearchResult(uid, storage.library(uid), entries));
    }

    List<LibrarySearchResult> searchSharedByModid(String modid, String queryText, int topK, float threshold) {
        return searchLibraries(storage.sharedByModid(modid), queryText, embedQueryVector(queryText), topK, threshold);
    }

    List<LibrarySearchResult> searchSharedByTags(List<String> tags, String queryText, int topK, float threshold) {
        return searchLibraries(storage.sharedByTags(tags), queryText, embedQueryVector(queryText), topK, threshold);
    }

    List<RagCacheManager.RagEntrySearchResult> searchInline(List<String> contents, String queryText, int topK, float threshold) {
        List<InlineEntry> entries = inlineEntries(contents);
        if (entries.isEmpty() || queryText == null || queryText.isBlank()) {
            return List.of();
        }
        try {
            int effectiveTopK = topK > 0 ? topK : DEFAULT_TOP_K;
            float effectiveThreshold = threshold > 0F && threshold <= 1F ? threshold : DEFAULT_THRESHOLD;
            List<String> texts = entries.stream().map(InlineEntry::content).toList();
            Map<String, Deque<String>> idsByContent = entryIdsByContent(entries);
            return inferenceClient.search(queryText, texts, effectiveTopK, effectiveThreshold).stream()
                    .map(result -> new RagCacheManager.RagEntrySearchResult(
                            nextEntryId(idsByContent, result.getContent()),
                            result.getContent(),
                            result.getScore()
                    ))
                    .toList();
        } catch (Exception exception) {
            environment.error("[LLMService] Inline RAG search failed", exception);
            return List.of();
        }
    }

    void shutdown() {
        storage.shutdown();
    }

    private List<RagCacheManager.RagEntrySearchResult> searchChunk(Chunk chunk, String queryText) {
        if (Boolean.TRUE.equals(chunk.getUseCache())) {
            return storage.searchEntries(chunk.getUid(), queryText, DEFAULT_TOP_K, DEFAULT_THRESHOLD);
        }
        List<String> contents = validTexts(chunk.getRagContent());
        return contents.isEmpty()
                ? List.of()
                : searchInline(contents, queryText, DEFAULT_TOP_K, DEFAULT_THRESHOLD);
    }

    private List<LibrarySearchResult> searchLibraries(
            List<RagLibraryRegistry.RagLibraryMeta> candidates,
            String queryText,
            float[] queryVector,
            int topK,
            float threshold
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<LibrarySearchResult> results = new ArrayList<>();
        for (RagLibraryRegistry.RagLibraryMeta library : candidates) {
            if (library == null || library.uid().isBlank()) {
                continue;
            }
            List<RagCacheManager.RagEntrySearchResult> entries = searchEntries(
                    library.uid(),
                    queryText,
                    queryVector,
                    topK,
                    threshold
            );
            if (!entries.isEmpty()) {
                results.add(new LibrarySearchResult(library.uid(), library, entries));
            }
        }
        return results;
    }

    private List<RagCacheManager.RagEntrySearchResult> applyTokenBudget(
            List<RagCacheManager.RagEntrySearchResult> results,
            Integer tokenBudget
    ) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        if (tokenBudget == null) {
            return results;
        }
        if (tokenBudget <= 0) {
            return List.of();
        }

        List<RagCacheManager.RagEntrySearchResult> selected = new ArrayList<>();
        StringBuilder contents = new StringBuilder();
        try {
            for (RagCacheManager.RagEntrySearchResult result : results) {
                String content = result == null || result.content() == null ? "" : result.content().trim();
                if (content.isEmpty()) {
                    continue;
                }
                int previousLength = contents.length();
                if (previousLength > 0) {
                    contents.append('\n');
                }
                contents.append(content);
                int tokens = inferenceClient.countChatPromptTokens(
                        List.of(new ChatMessage("system", contents.toString())),
                        SamplerConfig.defaults()
                );
                if (tokens <= tokenBudget) {
                    selected.add(result);
                } else {
                    contents.setLength(previousLength);
                }
            }
            return List.copyOf(selected);
        } catch (Exception unsupportedTokenizer) {
            return results;
        }
    }

    private String buildPrompt(List<RagCacheManager.RagEntrySearchResult> results, String prompt) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (prompt != null && !prompt.isBlank()) {
            builder.append(prompt).append('\n');
        }
        for (int index = 0; index < results.size(); index++) {
            builder.append(index + 1).append(". ").append(results.get(index).content()).append('\n');
        }
        return builder.toString();
    }

    private float[] embedQueryVector(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return null;
        }
        try {
            return storage.embedQueryVector(queryText);
        } catch (Exception exception) {
            environment.error("[LLMService] Failed to embed RAG query text", exception);
            return null;
        }
    }

    private static List<String> validTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return texts.stream()
                .filter(text -> text != null && !text.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static List<InlineEntry> inlineEntries(List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        List<InlineEntry> entries = new ArrayList<>();
        for (int index = 0; index < contents.size(); index++) {
            String content = contents.get(index);
            if (content != null && !content.isBlank()) {
                entries.add(new InlineEntry(Integer.toString(index), content.trim()));
            }
        }
        return entries;
    }

    private static Map<String, Deque<String>> entryIdsByContent(List<InlineEntry> entries) {
        Map<String, Deque<String>> ids = new LinkedHashMap<>();
        for (InlineEntry entry : entries) {
            ids.computeIfAbsent(entry.content(), ignored -> new ArrayDeque<>()).add(entry.entryId());
        }
        return ids;
    }

    private static String nextEntryId(Map<String, Deque<String>> idsByContent, String content) {
        if (idsByContent == null || content == null) {
            return "";
        }
        Deque<String> ids = idsByContent.get(content);
        return ids == null || ids.isEmpty() ? "" : ids.removeFirst();
    }

    record RagPreparation(List<RagCacheManager.RagEntrySearchResult> results, String prompt) {
        RagPreparation {
            results = results == null ? List.of() : List.copyOf(results);
            prompt = prompt == null ? "" : prompt;
        }

        static RagPreparation empty() {
            return new RagPreparation(List.of(), "");
        }
    }

    record LibrarySearchResult(
            String uid,
            RagLibraryRegistry.RagLibraryMeta library,
            List<RagCacheManager.RagEntrySearchResult> entries
    ) {
        LibrarySearchResult {
            uid = uid == null ? "" : uid.trim();
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    private record InlineEntry(String entryId, String content) {
    }
}
