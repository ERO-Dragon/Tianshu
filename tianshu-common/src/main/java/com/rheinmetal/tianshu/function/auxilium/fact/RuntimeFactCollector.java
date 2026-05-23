package com.rheinmetal.tianshu.function.auxilium.fact;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

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

    public RuntimeFactRefreshResult refreshForQuestion(AXScope scope, AXRequest request) {
        if (scope == null || !scope.writable()) {
            return RuntimeFactRefreshResult.skipped();
        }
        int producedFacts = 0;
        int changedFacts = 0;
        int refreshedProviders = 0;
        for (RuntimeFactProvider provider : providers) {
            RuntimeFactBatch batch = provider.refreshForQuestion(scope, request);
            if (batch == null || !batch.refreshed()) {
                continue;
            }
            refreshedProviders++;
            List<RuntimeFact> facts = batch.facts();
            producedFacts += facts.size();
            changedFacts += factPool.upsertAll(scope, facts);
        }
        factPool.pruneExpired(scope);
        return new RuntimeFactRefreshResult(refreshedProviders, producedFacts, changedFacts);
    }
}
