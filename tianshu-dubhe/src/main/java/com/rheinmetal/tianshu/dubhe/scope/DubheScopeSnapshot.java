package com.rheinmetal.tianshu.dubhe.scope;

public record DubheScopeSnapshot(DubheScopeKind kind, String stableIdentity, String displayName, String dimensionId, boolean writable) {
    public DubheScopeSnapshot {
        kind = kind == null ? DubheScopeKind.UNKNOWN : kind;
        stableIdentity = stableIdentity == null ? "" : stableIdentity.trim();
        displayName = displayName == null ? "" : displayName.trim();
        dimensionId = dimensionId == null ? "" : dimensionId.trim();
        writable = writable && kind != DubheScopeKind.UNKNOWN && !stableIdentity.isBlank();
    }

    public static DubheScopeSnapshot unknown() {
        return new DubheScopeSnapshot(DubheScopeKind.UNKNOWN, "", "Unknown World", "", false);
    }
}
