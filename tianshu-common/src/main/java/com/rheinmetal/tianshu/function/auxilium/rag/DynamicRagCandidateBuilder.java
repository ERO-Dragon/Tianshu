package com.rheinmetal.tianshu.function.auxilium.rag;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.fact.RuntimeFact;
import com.rheinmetal.tianshu.function.auxilium.fact.RuntimeFactPool;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.Comparator;
import java.util.List;

public final class DynamicRagCandidateBuilder {
    private final RuntimeFactPool factPool;
    private final DynamicRagUpdatePolicy policy;
    private final RuntimeFactTextRenderer textRenderer;
    private final AXPromptLanguageProvider languageProvider;

    public DynamicRagCandidateBuilder(RuntimeFactPool factPool, DynamicRagUpdatePolicy policy) {
        this(factPool, policy, new RuntimeFactTextRenderer(), AXPromptLanguageProvider.fixed(AXPromptLanguage.EN_US));
    }

    public DynamicRagCandidateBuilder(RuntimeFactPool factPool, DynamicRagUpdatePolicy policy, RuntimeFactTextRenderer textRenderer) {
        this(factPool, policy, textRenderer, AXPromptLanguageProvider.fixed(AXPromptLanguage.EN_US));
    }

    public DynamicRagCandidateBuilder(RuntimeFactPool factPool, DynamicRagUpdatePolicy policy, RuntimeFactTextRenderer textRenderer, AXPromptLanguageProvider languageProvider) {
        this.factPool = factPool;
        this.policy = policy == null ? DynamicRagUpdatePolicy.DEFAULT : policy;
        this.textRenderer = textRenderer == null ? new RuntimeFactTextRenderer() : textRenderer;
        this.languageProvider = languageProvider == null ? AXPromptLanguageProvider.fixed(AXPromptLanguage.EN_US) : languageProvider;
    }

    public List<DynamicRagCandidate> build(AXScope scope) {
        return build(scope, null);
    }

    public List<DynamicRagCandidate> build(AXScope scope, AXRequest request) {
        if (scope == null || !scope.writable() || factPool == null) {
            return List.of();
        }
        AXPromptLanguage language = languageProvider.currentLanguage();
        long now = System.currentTimeMillis();
        return factPool.snapshot(scope).stream()
                .map(fact -> fromRuntimeFact(fact, language))
                .filter(candidate -> !candidate.isEmpty() && !candidate.isExpired(now))
                .sorted(Comparator.comparingInt(DynamicRagCandidate::priority).reversed().thenComparing(DynamicRagCandidate::updatedAt).reversed())
                .limit(policy.maxCandidates())
                .toList();
    }

    public List<String> buildTexts(AXScope scope) {
        return build(scope).stream().filter(DynamicRagCandidate::shouldIncludeInRequestPackage).map(DynamicRagCandidate::text).toList();
    }

    private DynamicRagCandidate fromRuntimeFact(RuntimeFact fact, AXPromptLanguage language) {
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
