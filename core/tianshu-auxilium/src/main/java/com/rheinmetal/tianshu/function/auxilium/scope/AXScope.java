package com.rheinmetal.tianshu.function.auxilium.scope;

public record AXScope(String sharedUserId, String worldId, String displayName, AXScopeKind kind, boolean writable) {
    public AXScope {
        sharedUserId = normalize(sharedUserId, "default_user");
        worldId = normalize(worldId, "unknown_world");
        displayName = normalize(displayName, worldId);
        kind = kind == null ? AXScopeKind.UNKNOWN : kind;
        writable = writable && kind != AXScopeKind.UNKNOWN && !"unknown_world".equals(worldId);
    }

    public static AXScope unknown() {
        return new AXScope("default_user", "unknown_world", "Unknown World", AXScopeKind.UNKNOWN, false);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
