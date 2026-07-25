package com.rheinmetal.tianshu.neoforge.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoForgeConfigBoundaryTest {
    @Test
    void keepsOneClientTomlWithoutUnusedServicePortConfiguration() throws Exception {
        String config = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/config/ClientConfig.java"),
                StandardCharsets.UTF_8
        );
        String modEntry = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/TianshuNeoForge.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(modEntry.contains("registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ClientConfig.SPEC)"));
        assertFalse(config.contains("ASR_PORT"));
        assertFalse(config.contains("LLM_PORT"));
        assertFalse(config.contains("getAsrPort"));
        assertFalse(config.contains("getLlmPort"));
        assertFalse(config.contains("AI_ENABLED"));
        assertFalse(config.contains("isAiEnabled"));
    }

    @Test
    void loaderProjectOnlyDefinesRunsThatMatchTheClientOnlyProduct() throws Exception {
        String build = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);

        assertTrue(build.contains("client {"));
        assertTrue(build.contains("data {"));
        assertFalse(build.contains("server {"));
        assertFalse(build.contains("gameTestServer {"));
    }
}
