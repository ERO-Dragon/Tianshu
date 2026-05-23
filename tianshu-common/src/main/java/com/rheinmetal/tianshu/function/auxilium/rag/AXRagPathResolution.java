package com.rheinmetal.tianshu.function.auxilium.rag;

import com.rheinmetal.tianshu.protocol.payload.LlmRagPathResultPayload;

import java.nio.file.Path;

public record AXRagPathResolution(
        String requestId,
        String worldId,
        String moduleId,
        String agentId,
        Path staticRagRoot,
        Path memoryRagRoot,
        Path memoriesFile
) {
    public AXRagPathResolution {
        requestId = normalize(requestId);
        worldId = normalize(worldId);
        moduleId = normalize(moduleId);
        agentId = normalize(agentId);
    }

    public boolean valid() {
        return memoryRagRoot != null && memoriesFile != null;
    }

    public static AXRagPathResolution fromPayload(LlmRagPathResultPayload payload) {
        if (payload == null || !"OK".equals(payload.status())) {
            return empty();
        }
        return new AXRagPathResolution(
                payload.requestId(),
                payload.worldId(),
                payload.moduleId(),
                payload.agentId(),
                toPath(payload.staticRagRoot()),
                toPath(payload.memoryRagRoot()),
                toPath(payload.memoriesFile())
        );
    }

    public static AXRagPathResolution empty() {
        return new AXRagPathResolution("", "", "", "", null, null, null);
    }

    private static Path toPath(String value) {
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
