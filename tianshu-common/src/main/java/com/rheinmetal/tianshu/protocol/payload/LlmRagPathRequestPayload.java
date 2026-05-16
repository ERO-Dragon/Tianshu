package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record LlmRagPathRequestPayload(
        String requestId,
        String moduleId,
        String agentId
) implements ITianshuPayload {
    public LlmRagPathRequestPayload {
        requestId = normalize(requestId, "llm.rag.path");
        moduleId = normalize(moduleId, "unknown_module");
        agentId = normalize(agentId, "default_agent");
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
