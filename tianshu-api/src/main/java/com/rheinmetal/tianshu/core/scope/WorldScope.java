package com.rheinmetal.tianshu.core.scope;

public record WorldScope(
        String sharedUserId,
        String worldId,
        String displayName,
        WorldScopeKind kind,
        boolean writable
) {
    public WorldScope {
        sharedUserId = normalize(sharedUserId, "default_user");
        worldId = normalize(worldId, "unknown_world");
        displayName = normalize(displayName, worldId);
        kind = kind == null ? WorldScopeKind.UNKNOWN : kind;
        writable = writable && kind != WorldScopeKind.UNKNOWN && !"unknown_world".equals(worldId);
    }

    public static WorldScope unknown() {
        return new WorldScope("default_user", "unknown_world", "Unknown World", WorldScopeKind.UNKNOWN, false);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
