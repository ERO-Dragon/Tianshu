package com.rheinmetal.tianshu.function.auxilium.module.gamecontext;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmRagClient;
import com.rheinmetal.tianshu.protocol.payload.LLMCacheManageResultPayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class AXSharedKnowledgePlanner implements AXGameContextKnowledgePlanner {
    private static final List<String> DIRECT_STATIC_TAGS = List.of("main");
    private static final List<String> DYNAMIC_STATIC_TAGS = List.of("main", "addon");
    private static final float DEFAULT_THRESHOLD = 0.7F;
    private static final String DYNAMIC_INLINE_UID = "module.ax.dynamic_facts.inline";

    private final AXLlmRagClient ragClient;
    private final long timeoutMillis;

    public AXSharedKnowledgePlanner(AXLlmRagClient ragClient) {
        this(ragClient, 350L);
    }

    public AXSharedKnowledgePlanner(AXLlmRagClient ragClient, long timeoutMillis) {
        this.ragClient = Objects.requireNonNull(ragClient, "ragClient");
        this.timeoutMillis = Math.max(0L, timeoutMillis);
    }

    @Override
    public List<AXKnowledgeHit> plan(AXRequest request, AXContextSnapshot context, AXContextBudget budget) {
        String query = request == null ? "" : request.userText();
        if (query.isBlank()) {
            return List.of();
        }
        AXContextBudget effectiveBudget = budget == null ? AXContextBudget.DEFAULT : budget;
        List<AXKnowledgeHit> hits = new ArrayList<>();
        hits.addAll(search(DIRECT_STATIC_TAGS, query, Math.max(1, effectiveBudget.maxStaticContentItems()), AXKnowledgeHit.QueryPath.INPUT_RAG));
        List<String> dynamicFacts = searchDynamicFacts(query, context, Math.max(1, effectiveBudget.maxDynamicContentItems()));
        if (!dynamicFacts.isEmpty()) {
            hits.add(AXKnowledgeHit.dynamicFacts(DYNAMIC_INLINE_UID, dynamicFacts));
            String dynamicQuery = dynamicQuery(query, dynamicFacts);
            hits.addAll(search(DYNAMIC_STATIC_TAGS, dynamicQuery, Math.max(1, effectiveBudget.maxDynamicContentItems()), AXKnowledgeHit.QueryPath.DYNAMIC_RAG));
        }
        return hits.stream()
                .filter(hit -> hit != null && !hit.facts().isEmpty())
                .toList();
    }

    private List<AXKnowledgeHit> search(List<String> tags, String queryText, int topK, AXKnowledgeHit.QueryPath path) {
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
                    .map(entry -> new AXKnowledgeHit(entry.getKey(), entry.getValue(), path))
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> searchDynamicFacts(String queryText, AXContextSnapshot context, int topK) {
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

    private List<String> dynamicFactCandidates(AXContextSnapshot context) {
        if (context == null || context.dynamicFacts().isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        return context.dynamicFacts().stream()
                .filter(fact -> fact != null && !fact.isEmpty() && !fact.isExpired(now))
                .map(AXDynamicFact::text)
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
