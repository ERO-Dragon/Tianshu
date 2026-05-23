package com.rheinmetal.tianshu.function.auxilium.rag;

public record DynamicRagUpdatePolicy(boolean refreshOnQuestion, boolean refreshOnEvent, int maxCandidates) {
    public static final DynamicRagUpdatePolicy DEFAULT = new DynamicRagUpdatePolicy(true, true, 32);

    public DynamicRagUpdatePolicy {
        maxCandidates = Math.max(0, maxCandidates);
    }
}
