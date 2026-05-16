package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record LlmRagPathResultPayload(
        String requestId,
        String status,
        String worldId,
        String moduleId,
        String agentId,
        String profile,
        String ragRoot,
        String worldRoot,
        String profilesFile,
        String moduleRoot,
        String staticRagRoot,
        String agentRoot,
        String memoryRagRoot,
        String memoriesFile,
        String errorCode,
        String errorMessage
) implements ITianshuPayload {
    public LlmRagPathResultPayload {
        requestId = normalize(requestId, "llm.rag.path");
        status = normalize(status, "FAILED");
        worldId = optional(worldId);
        moduleId = optional(moduleId);
        agentId = optional(agentId);
        profile = optional(profile);
        ragRoot = optional(ragRoot);
        worldRoot = optional(worldRoot);
        profilesFile = optional(profilesFile);
        moduleRoot = optional(moduleRoot);
        staticRagRoot = optional(staticRagRoot);
        agentRoot = optional(agentRoot);
        memoryRagRoot = optional(memoryRagRoot);
        memoriesFile = optional(memoriesFile);
        errorCode = optional(errorCode);
        errorMessage = optional(errorMessage);
    }

    public static LlmRagPathResultPayload failed(String requestId, String code, String message) {
        return new LlmRagPathResultPayload(requestId, "FAILED", "", "", "", "", "", "", "", "", "", "", "", "", code, message);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String optional(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }
}
