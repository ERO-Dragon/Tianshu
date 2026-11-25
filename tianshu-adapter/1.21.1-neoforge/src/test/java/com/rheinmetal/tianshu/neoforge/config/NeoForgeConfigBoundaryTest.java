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

    /**
     * The product is client-first with an optional server component, so the loader project must define the runs that
     * shape needs: the client, the server component, data generation — and the game-test server.
     *
     * <p>The game-test run was not part of this list until the operations that only exist in a real world (placing a
     * block, moving items through a container, burning a furnace) got tests at all; a client bench cannot answer those.
     * It must keep its own game directory: a test that inherits a developer's world, configs or leftover jars is not a
     * test.</p>
     */
    @Test
    void loaderProjectDefinesTheRunsTheProductNeeds() throws Exception {
        String build = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);

        assertTrue(build.contains("client {"));
        assertTrue(build.contains("server {"), "the optional server component needs a development run");
        assertTrue(build.contains("data {"));
        assertTrue(build.contains("gameTestServer {"), "the world-state tests need a run of their own");
        assertTrue(build.contains("gameDirectory = file('run-gametest')"),
                "the game-test run must not share the developer's world");
    }
}
