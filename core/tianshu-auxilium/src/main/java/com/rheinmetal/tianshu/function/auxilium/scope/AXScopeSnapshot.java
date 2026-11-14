package com.rheinmetal.tianshu.function.auxilium.scope;

public record AXScopeSnapshot(AXScopeKind kind, String stableIdentity, String displayName, String dimensionId, boolean writable) {
    public AXScopeSnapshot {
        kind = kind == null ? AXScopeKind.UNKNOWN : kind;
        stableIdentity = stableIdentity == null ? "" : stableIdentity.trim();
        displayName = displayName == null ? "" : displayName.trim();
        dimensionId = dimensionId == null ? "" : dimensionId.trim();
        writable = writable && kind != AXScopeKind.UNKNOWN && !stableIdentity.isBlank();
    }

    public static AXScopeSnapshot unknown() {
        return new AXScopeSnapshot(AXScopeKind.UNKNOWN, "", "Unknown World", "", false);
    }
}
