package com.rheinmetal.tianshu.function.auxilium.fact;

public record RuntimeFactRefreshResult(
        int providers,
        int producedFacts,
        int changedFacts
) {
    public RuntimeFactRefreshResult {
        providers = Math.max(0, providers);
        producedFacts = Math.max(0, producedFacts);
        changedFacts = Math.max(0, changedFacts);
    }

    public static RuntimeFactRefreshResult skipped() {
        return new RuntimeFactRefreshResult(0, 0, 0);
    }

    public boolean changedAny() {
        return changedFacts > 0;
    }
}
