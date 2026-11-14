package com.rheinmetal.tianshu.core.scope;

public record WorldIdentitySnapshot(
        WorldScopeKind kind,
        String stableIdentity,
        String displayName,
        String dimensionId,
        boolean writable
) {
    public WorldIdentitySnapshot {
        kind = kind == null ? WorldScopeKind.UNKNOWN : kind;
        stableIdentity = stableIdentity == null ? "" : stableIdentity.trim();
        displayName = displayName == null ? "" : displayName.trim();
        dimensionId = dimensionId == null ? "" : dimensionId.trim();
        writable = writable && kind != WorldScopeKind.UNKNOWN && !stableIdentity.isBlank();
    }

    public static WorldIdentitySnapshot unknown() {
        return new WorldIdentitySnapshot(WorldScopeKind.UNKNOWN, "", "Unknown World", "", false);
    }
}
