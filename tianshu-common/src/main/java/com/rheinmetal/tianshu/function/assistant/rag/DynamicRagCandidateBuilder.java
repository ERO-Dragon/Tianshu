package com.rheinmetal.tianshu.function.assistant.rag;

import com.rheinmetal.tianshu.function.assistant.AssistantRequest;
import com.rheinmetal.tianshu.function.assistant.fact.RuntimeFact;
import com.rheinmetal.tianshu.function.assistant.fact.RuntimeFactPool;
import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptLanguage;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;

import java.util.Comparator;
import java.util.List;

public final class DynamicRagCandidateBuilder {
    private final RuntimeFactPool factPool;
    private final DynamicRagUpdatePolicy policy;
    private final RuntimeFactTextRenderer textRenderer;

    public DynamicRagCandidateBuilder(RuntimeFactPool factPool, DynamicRagUpdatePolicy policy) {
        this(factPool, policy, new RuntimeFactTextRenderer());
    }

    public DynamicRagCandidateBuilder(RuntimeFactPool factPool, DynamicRagUpdatePolicy policy, RuntimeFactTextRenderer textRenderer) {
        this.factPool = factPool;
        this.policy = policy == null ? DynamicRagUpdatePolicy.DEFAULT : policy;
        this.textRenderer = textRenderer == null ? new RuntimeFactTextRenderer() : textRenderer;
    }

    public List<DynamicRagCandidate> build(AssistantScope scope) {
        return build(scope, null);
    }

    public List<DynamicRagCandidate> build(AssistantScope scope, AssistantRequest request) {
        if (scope == null || !scope.writable() || factPool == null) {
            return List.of();
        }
        AssistantPromptLanguage language = AssistantPromptLanguage.fromText(request == null ? "" : request.userText());
        long now = System.currentTimeMillis();
        return factPool.snapshot(scope).stream()
                .map(fact -> fromRuntimeFact(fact, language))
                .filter(candidate -> !candidate.isEmpty() && !candidate.isExpired(now))
                .sorted(Comparator.comparingInt(DynamicRagCandidate::priority).reversed().thenComparing(DynamicRagCandidate::updatedAt).reversed())
                .limit(policy.maxCandidates())
                .toList();
    }

    public List<String> buildTexts(AssistantScope scope) {
        return build(scope).stream().filter(DynamicRagCandidate::shouldIncludeInRequestPackage).map(DynamicRagCandidate::text).toList();
    }

    private DynamicRagCandidate fromRuntimeFact(RuntimeFact fact, AssistantPromptLanguage language) {
        return new DynamicRagCandidate(
                "rag.runtime_fact." + fact.factId(),
                textRenderer.render(fact, language),
                fact.importance(),
                fact.source(),
                DynamicRagSourceKind.RUNTIME_FACT,
                fact.subject(),
                fact.tags(),
                fact.updatedAt(),
                fact.ttlMillis(),
                DynamicRagExposure.REQUEST_DYNAMIC_RAG
        );
    }
}
