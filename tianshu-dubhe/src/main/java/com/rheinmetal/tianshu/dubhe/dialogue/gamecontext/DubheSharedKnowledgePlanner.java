package com.rheinmetal.tianshu.dubhe.dialogue.gamecontext;

import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextBudget;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextSnapshot;
import com.rheinmetal.tianshu.dubhe.agent.llm.DubheLlmRagClient;
import com.rheinmetal.tianshu.weaving.contract.LLMCacheManageResultPayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class DubheSharedKnowledgePlanner implements DubheGameContextKnowledgePlanner {
    private static final List<String> DIRECT_STATIC_TAGS = List.of("main", "addon");
    private static final List<String> DYNAMIC_STATIC_TAGS = List.of("main", "addon");
    private static final float DEFAULT_THRESHOLD = 0.7F;
    private static final String DYNAMIC_INLINE_UID = "module.dubhe.dynamic_facts.inline";

    private final DubheLlmRagClient ragClient;
    private final long timeoutMillis;

    public DubheSharedKnowledgePlanner(DubheLlmRagClient ragClient) {
        this(ragClient, 350L);
    }

    public DubheSharedKnowledgePlanner(DubheLlmRagClient ragClient, long timeoutMillis) {
        this.ragClient = Objects.requireNonNull(ragClient, "ragClient");
        this.timeoutMillis = Math.max(0L, timeoutMillis);
    }

    @Override
    public List<DubheKnowledgeHit> plan(DubheRequest request, DubheContextSnapshot context, DubheContextBudget budget) {
        String query = request == null ? "" : request.userText();
        if (query.isBlank()) {
            return List.of();
        }
        DubheContextBudget effectiveBudget = budget == null ? DubheContextBudget.DEFAULT : budget;
        int knowledgeLimit = Math.max(1, effectiveBudget.maxKnowledgeRagItems());
        List<DubheKnowledgeHit> hits = new ArrayList<>();
        hits.addAll(search(DIRECT_STATIC_TAGS, query, knowledgeLimit, DubheKnowledgeHit.QueryPath.INPUT_RAG));
        List<String> dynamicFacts = searchDynamicFacts(query, context, knowledgeLimit);
        if (!dynamicFacts.isEmpty()) {
            hits.add(DubheKnowledgeHit.dynamicFacts(DYNAMIC_INLINE_UID, dynamicFacts));
            String dynamicQuery = dynamicQuery(query, dynamicFacts);
            hits.addAll(search(DYNAMIC_STATIC_TAGS, dynamicQuery, knowledgeLimit, DubheKnowledgeHit.QueryPath.DYNAMIC_RAG));
        }
        return hits.stream()
                .filter(hit -> hit != null && !hit.facts().isEmpty())
                .toList();
    }

    private List<DubheKnowledgeHit> search(List<String> tags, String queryText, int topK, DubheKnowledgeHit.QueryPath path) {
        try {
            LLMCacheManageResultPayload result = ragClient.searchTags(tags, queryText, topK, DEFAULT_THRESHOLD)
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (result == null || !result.success() || result.hits().isEmpty()) {
                return List.of();
            }
            Map<String, List<String>> factsByUid = new LinkedHashMap<>();
            for (LLMCacheManageResultPayload.HitGroupPayload group : result.hits()) {
                if (group == null || group.entries().isEmpty()) {
                    continue;
                }
                List<String> facts = factsByUid.computeIfAbsent(group.uid(), ignored -> new ArrayList<>());
                group.entries().stream()
                        .filter(Objects::nonNull)
                        .map(LLMCacheManageResultPayload.HitEntryPayload::content)
                        .filter(content -> content != null && !content.isBlank())
                        .map(String::trim)
                        .forEach(facts::add);
            }
            return factsByUid.entrySet().stream()
                    .map(entry -> new DubheKnowledgeHit(entry.getKey(), entry.getValue(), path))
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> searchDynamicFacts(String queryText, DubheContextSnapshot context, int topK) {
        List<String> candidates = dynamicFactCandidates(context);
        if (queryText == null || queryText.isBlank() || candidates.isEmpty()) {
            return List.of();
        }
        try {
            LLMCacheManageResultPayload result = ragClient.searchInlineContents(DYNAMIC_INLINE_UID, queryText, candidates, topK, DEFAULT_THRESHOLD)
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (result == null || !result.success() || result.hits().isEmpty()) {
                return List.of();
            }
            return result.hits().stream()
                    .filter(Objects::nonNull)
                    .flatMap(group -> group.entries().stream())
                    .filter(Objects::nonNull)
                    .map(hit -> dynamicFactByEntryId(candidates, hit.entryId()))
                    .filter(content -> content != null && !content.isBlank())
                    .map(String::trim)
                    .distinct()
                    .limit(topK)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String dynamicFactByEntryId(List<String> candidates, String entryId) {
        if (candidates == null || candidates.isEmpty() || entryId == null || entryId.isBlank()) {
            return "";
        }
        try {
            int index = Integer.parseInt(entryId.trim());
            return index >= 0 && index < candidates.size() ? candidates.get(index) : "";
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private List<String> dynamicFactCandidates(DubheContextSnapshot context) {
        if (context == null || context.dynamicFacts().isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        return context.dynamicFacts().stream()
                .filter(fact -> fact != null && !fact.isEmpty() && !fact.isExpired(now))
                .map(DubheDynamicFact::text)
                .filter(text -> text != null && !text.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String dynamicQuery(String input, List<String> dynamicFacts) {
        StringBuilder builder = new StringBuilder(input == null ? "" : input.trim());
        if (dynamicFacts != null) {
            dynamicFacts.stream()
                    .filter(text -> text != null && !text.isBlank())
                    .map(String::trim)
                    .distinct()
                    .forEach(text -> builder.append('\n').append(text));
        }
        return builder.toString().trim();
    }
}
