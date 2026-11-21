package com.rheinmetal.tianshu.dubhe.shared.scope;

public record DubheScope(String sharedUserId, String worldId, String displayName, DubheScopeKind kind, boolean writable) {
    public DubheScope {
        sharedUserId = normalize(sharedUserId, "default_user");
        worldId = normalize(worldId, "unknown_world");
        displayName = normalize(displayName, worldId);
        kind = kind == null ? DubheScopeKind.UNKNOWN : kind;
        writable = writable && kind != DubheScopeKind.UNKNOWN && !"unknown_world".equals(worldId);
    }

    public static DubheScope unknown() {
        return new DubheScope("default_user", "unknown_world", "Unknown World", DubheScopeKind.UNKNOWN, false);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
