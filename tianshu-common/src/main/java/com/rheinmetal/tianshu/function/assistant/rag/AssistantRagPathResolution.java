package com.rheinmetal.tianshu.function.assistant.rag;

import com.rheinmetal.tianshu.protocol.payload.LlmRagPathResultPayload;

import java.nio.file.Path;

public record AssistantRagPathResolution(
        String requestId,
        String worldId,
        String moduleId,
        String agentId,
        Path staticRagRoot,
        Path memoryRagRoot,
        Path memoriesFile
) {
    public AssistantRagPathResolution {
        requestId = normalize(requestId);
        worldId = normalize(worldId);
        moduleId = normalize(moduleId);
        agentId = normalize(agentId);
    }

    public boolean valid() {
        return memoryRagRoot != null && memoriesFile != null;
    }

    public static AssistantRagPathResolution fromPayload(LlmRagPathResultPayload payload) {
        if (payload == null || !"OK".equals(payload.status())) {
            return empty();
        }
        return new AssistantRagPathResolution(
                payload.requestId(),
                payload.worldId(),
                payload.moduleId(),
                payload.agentId(),
                toPath(payload.staticRagRoot()),
                toPath(payload.memoryRagRoot()),
                toPath(payload.memoriesFile())
        );
    }

    public static AssistantRagPathResolution empty() {
        return new AssistantRagPathResolution("", "", "", "", null, null, null);
    }

    private static Path toPath(String value) {
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
