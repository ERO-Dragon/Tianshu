package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.integration.IntegrationModuleDeclaration;

public record IntegrationModuleRegisterPayload(IntegrationModuleDeclaration declaration, long timestampMillis) implements ITianshuPayload {
    public IntegrationModuleRegisterPayload {
        if (declaration == null) {
            throw new IllegalArgumentException("declaration cannot be null");
        }
        if (timestampMillis <= 0L) timestampMillis = System.currentTimeMillis();
    }
}
