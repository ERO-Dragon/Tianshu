package com.rheinmetal.tianshu.function.assistant.fact;

import com.rheinmetal.tianshu.function.assistant.AssistantRequest;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RuntimeFactCollector {
    private final RuntimeFactPool factPool;
    private final List<RuntimeFactProvider> providers = new CopyOnWriteArrayList<>();

    public RuntimeFactCollector(RuntimeFactPool factPool) {
        this.factPool = factPool;
    }

    public void registerProvider(RuntimeFactProvider provider) {
        if (provider != null) {
            providers.add(provider);
        }
    }

    public RuntimeFactRefreshResult refreshForQuestion(AssistantScope scope, AssistantRequest request) {
        if (scope == null || !scope.writable()) {
            return RuntimeFactRefreshResult.skipped();
        }
        int producedFacts = 0;
        int changedFacts = 0;
        for (RuntimeFactProvider provider : providers) {
            List<RuntimeFact> facts = provider.refreshForQuestion(scope, request);
            producedFacts += facts == null ? 0 : facts.size();
            changedFacts += factPool.upsertAll(scope, facts);
        }
        factPool.pruneExpired(scope);
        return new RuntimeFactRefreshResult(providers.size(), producedFacts, changedFacts);
    }
}
