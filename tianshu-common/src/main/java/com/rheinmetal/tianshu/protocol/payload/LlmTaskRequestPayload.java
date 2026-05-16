package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

public record LlmTaskRequestPayload(
        String taskId,
        String purpose,
        LlmTaskUsageKind usageKind,
        List<LlmTaskMessagePayload> messages,
        List<String> dynamicFacts,
        int taskPriority,
        boolean taskPreemptible,
        boolean stream,
        boolean thinking,
        boolean useRag,
        int maxTokens,
        double temperature,
        long expireAtMillis,
        String moduleId,
        String agentId,
        String staticScope,
        List<String> staticMods,
        LlmUsageAuthorizationPayload authorization
) implements ITianshuPayload {
    public LlmTaskRequestPayload {
        taskId = normalizeOptional(taskId);
        purpose = normalizePurpose(purpose);
        usageKind = usageKind == null ? LlmTaskUsageKind.TASK : usageKind;
        messages = messages == null ? List.of() : List.copyOf(messages.stream().filter(message -> message != null).toList());
        dynamicFacts = dynamicFacts == null ? List.of() : List.copyOf(dynamicFacts.stream().filter(fact -> fact != null && !fact.isBlank()).map(String::trim).toList());
        if (Double.isNaN(temperature) || Double.isInfinite(temperature) || temperature < 0.0D || temperature > 2.0D) {
            temperature = 0.2D;
        }
        if (maxTokens < 0) {
            maxTokens = 0;
        }
        moduleId = normalizeOptional(moduleId);
        agentId = normalizeOptional(agentId);
        staticScope = normalizeStaticScope(staticScope);
        staticMods = staticMods == null ? List.of() : List.copyOf(staticMods.stream()
                .map(LlmTaskRequestPayload::normalizeOptional)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList());
        if (!"list".equals(staticScope)) {
            staticMods = List.of();
        }
        authorization = authorization == null ? LlmUsageAuthorizationPayload.EMPTY : authorization;
    }

    public LlmTaskRequestPayload(String taskId, String purpose, List<LlmTaskMessagePayload> messages, List<String> dynamicFacts, int taskPriority, boolean taskPreemptible, boolean stream, boolean thinking, boolean useRag, int maxTokens, double temperature, long expireAtMillis) {
        this(taskId, purpose, LlmTaskUsageKind.TASK, messages, dynamicFacts, taskPriority, taskPreemptible, stream, thinking, useRag, maxTokens, temperature, expireAtMillis, "", "", "", List.of(), LlmUsageAuthorizationPayload.EMPTY);
    }

    public LlmTaskRequestPayload(String taskId, String purpose, List<LlmTaskMessagePayload> messages, List<String> dynamicFacts, int taskPriority, boolean taskPreemptible, boolean stream, boolean thinking, boolean useRag, int maxTokens, double temperature, long expireAtMillis, String world, String profile, String staticScope, List<String> staticMods) {
        this(taskId, purpose, LlmTaskUsageKind.TASK, messages, dynamicFacts, taskPriority, taskPreemptible, stream, thinking, useRag, maxTokens, temperature, expireAtMillis, moduleFromProfile(profile), agentFromProfile(profile), staticScope, staticMods, LlmUsageAuthorizationPayload.EMPTY);
    }

    private static String normalizePurpose(String value) {
        if (value == null || value.isBlank()) {
            return "llm.task";
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }

    private static String normalizeStaticScope(String value) {
        String normalized = normalizeOptional(value).toLowerCase();
        return switch (normalized) {
            case "none", "mod", "world", "list" -> normalized;
            default -> "";
        };
    }

    private static String moduleFromProfile(String profile) {
        String normalized = normalizeOptional(profile);
        int separator = normalized.indexOf('/');
        if (separator <= 0) {
            return normalized;
        }
        return normalized.substring(0, separator);
    }

    private static String agentFromProfile(String profile) {
        String normalized = normalizeOptional(profile);
        int separator = normalized.indexOf('/');
        if (separator < 0 || separator + 1 >= normalized.length()) {
            return "";
        }
        return normalized.substring(separator + 1);
    }
}
