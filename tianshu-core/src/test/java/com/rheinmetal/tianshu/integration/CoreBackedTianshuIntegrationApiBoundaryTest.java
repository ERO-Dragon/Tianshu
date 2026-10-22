package com.rheinmetal.tianshu.integration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class CoreBackedTianshuIntegrationApiBoundaryTest {
    @Test
    void integrationApiDoesNotBypassCoreBoundaryThroughProtocolRuntime() throws Exception {
        Path source = Path.of(
                "src/main/java/com/rheinmetal/tianshu/integration/CoreBackedTianshuIntegrationApi.java"
        );

        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("protocolRuntime()"));
        assertFalse(code.contains("getProtocolRuntime()"));
    }

    @Test
    void publicIntegrationContractDoesNotExposeCoreOrHostImplementations() throws Exception {
        Path source = Path.of(
                "src/main/java/com/rheinmetal/tianshu/integration/TianshuIntegrationApi.java"
        );

        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("TianshuCoreManager"));
        assertFalse(code.contains("ProtocolRuntime"));
        assertFalse(code.contains("net.minecraft"));
        assertFalse(code.contains("net.neoforged"));
    }
}
