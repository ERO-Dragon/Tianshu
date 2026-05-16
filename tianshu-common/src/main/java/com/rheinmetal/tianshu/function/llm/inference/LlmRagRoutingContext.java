package com.rheinmetal.tianshu.function.llm.inference;

import java.util.List;

public record LlmRagRoutingContext(
        String world,
        String moduleId,
        String agentId,
        String staticScope,
        List<String> staticMods
) {
    public static final LlmRagRoutingContext EMPTY = new LlmRagRoutingContext("", "", "", "", List.of());

    public LlmRagRoutingContext {
        world = normalizeOptional(world);
        moduleId = normalizeOptional(moduleId);
        agentId = normalizeOptional(agentId);
        staticScope = normalizeStaticScope(staticScope);
        staticMods = staticMods == null ? List.of() : List.copyOf(staticMods.stream()
                .map(LlmRagRoutingContext::normalizeOptional)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList());
        if (!"list".equals(staticScope)) {
            staticMods = List.of();
        }
    }

    public String profile() {
        if (moduleId.isBlank()) {
            return agentId;
        }
        if (agentId.isBlank()) {
            return moduleId;
        }
        return moduleId + "/" + agentId;
    }

    public boolean isEmpty() {
        return world.isBlank() && moduleId.isBlank() && agentId.isBlank() && staticScope.isBlank() && staticMods.isEmpty();
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim().replaceAll("[^a-zA-Z0-9._:-]", "_");
    }

    private static String normalizeStaticScope(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return switch (normalized) {
            case "none", "mod", "world", "list" -> normalized;
            default -> "";
        };
    }
}
