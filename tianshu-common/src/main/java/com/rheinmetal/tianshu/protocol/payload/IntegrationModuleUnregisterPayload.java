package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record IntegrationModuleUnregisterPayload(String moduleId, long timestampMillis) implements ITianshuPayload {
    public IntegrationModuleUnregisterPayload {
        if (moduleId == null || moduleId.isBlank()) {
            throw new IllegalArgumentException("moduleId cannot be blank");
        }
        moduleId = moduleId.trim();
        if (timestampMillis <= 0L) timestampMillis = System.currentTimeMillis();
    }
}
