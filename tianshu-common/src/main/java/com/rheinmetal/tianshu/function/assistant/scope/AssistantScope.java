package com.rheinmetal.tianshu.function.assistant.scope;

public record AssistantScope(String sharedUserId, String worldId, String displayName, AssistantScopeKind kind, boolean writable) {
    public AssistantScope {
        sharedUserId = normalize(sharedUserId, "default_user");
        worldId = normalize(worldId, "unknown_world");
        displayName = normalize(displayName, worldId);
        kind = kind == null ? AssistantScopeKind.UNKNOWN : kind;
        writable = writable && kind != AssistantScopeKind.UNKNOWN && !"unknown_world".equals(worldId);
    }

    public static AssistantScope unknown() {
        return new AssistantScope("default_user", "unknown_world", "Unknown World", AssistantScopeKind.UNKNOWN, false);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
