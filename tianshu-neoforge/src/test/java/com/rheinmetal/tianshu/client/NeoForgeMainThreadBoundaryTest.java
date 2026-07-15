package com.rheinmetal.tianshu.client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeMainThreadBoundaryTest {
    @Test
    void tickAndWorldEventsOnlyForwardBoundedWork() throws Exception {
        String client = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/client/TianshuClient.java"),
                StandardCharsets.UTF_8
        );
        String tick = methodBody(client, "public static void onClientTick");

        assertTrue(client.contains("lifecycleAdapter.onWorldLogin()"));
        assertTrue(client.contains("lifecycleAdapter.onWorldLogout()"));
        assertFalse(client.contains("startRuntimeSession().join()"));
        assertFalse(client.contains("stopRuntimeSession().join()"));
        assertFalse(tick.contains("Thread.sleep"));
        assertFalse(tick.contains("Files."));
        assertFalse(tick.contains(".join()"));
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        int opening = source.indexOf('{', start);
        int depth = 0;
        for (int index = opening; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') depth++;
            if (value == '}' && --depth == 0) {
                return source.substring(opening + 1, index);
            }
        }
        throw new AssertionError("Missing method: " + signature);
    }
}
