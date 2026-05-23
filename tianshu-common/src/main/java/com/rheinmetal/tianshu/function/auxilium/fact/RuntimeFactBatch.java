package com.rheinmetal.tianshu.function.auxilium.fact;

import java.util.List;

public record RuntimeFactBatch(
        String source,
        boolean refreshed,
        List<RuntimeFact> facts
) {
    public RuntimeFactBatch {
        source = source == null ? "" : source.trim();
        facts = facts == null ? List.of() : List.copyOf(facts.stream().filter(fact -> fact != null && !fact.isEmpty()).toList());
    }

    public static RuntimeFactBatch unchanged(String source) {
        return new RuntimeFactBatch(source, false, List.of());
    }

    public static RuntimeFactBatch refreshed(String source, List<RuntimeFact> facts) {
        return new RuntimeFactBatch(source, true, facts);
    }
}
