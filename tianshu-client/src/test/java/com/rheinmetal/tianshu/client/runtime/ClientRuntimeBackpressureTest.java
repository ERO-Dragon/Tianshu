package com.rheinmetal.tianshu.client.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRuntimeBackpressureTest {
    @Test
    void clientOwnedWorkersUseBoundedQueuesAndExplicitExecutors() throws Exception {
        String audio = source("com/rheinmetal/tianshu/client/audio/AudioManager.java");
        String gpu = source("com/rheinmetal/tianshu/client/llm/performance/GpuInfo.java");
        String presence = source("com/rheinmetal/tianshu/client/presence/context/PresenceContextQueryCoordinator.java");

        assertTrue(audio.contains("new ArrayBlockingQueue<>(8)"));
        assertFalse(audio.contains("Executors.newFixedThreadPool"));
        assertTrue(gpu.contains("new ArrayBlockingQueue<>(1)"));
        assertTrue(gpu.contains("}, DETECTION_EXECUTOR)"));
        assertTrue(presence.contains("new ArrayBlockingQueue<>(MAX_PENDING_QUERIES)"));
        assertTrue(presence.contains("\"PRESENCE_BUSY\""));
    }

    private static String source(String relativePath) throws Exception {
        return Files.readString(Path.of("src/main/java").resolve(relativePath), StandardCharsets.UTF_8);
    }
}
