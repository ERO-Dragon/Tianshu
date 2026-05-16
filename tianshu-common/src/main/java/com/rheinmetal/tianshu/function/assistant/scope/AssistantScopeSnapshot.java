package com.rheinmetal.tianshu.function.assistant.scope;

public record AssistantScopeSnapshot(AssistantScopeKind kind, String stableIdentity, String displayName, String dimensionId, boolean writable) {
    public AssistantScopeSnapshot {
        kind = kind == null ? AssistantScopeKind.UNKNOWN : kind;
        stableIdentity = stableIdentity == null ? "" : stableIdentity.trim();
        displayName = displayName == null ? "" : displayName.trim();
        dimensionId = dimensionId == null ? "" : dimensionId.trim();
        writable = writable && kind != AssistantScopeKind.UNKNOWN && !stableIdentity.isBlank();
    }

    public static AssistantScopeSnapshot unknown() {
        return new AssistantScopeSnapshot(AssistantScopeKind.UNKNOWN, "", "Unknown World", "", false);
    }
}
