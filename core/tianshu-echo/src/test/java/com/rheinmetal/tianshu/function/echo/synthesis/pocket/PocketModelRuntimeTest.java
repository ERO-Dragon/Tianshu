package com.rheinmetal.tianshu.function.echo.synthesis.pocket;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the native-resource lifetime rules of {@link PocketModelRuntime}.
 *
 * <p>These are source assertions, not behavioural ones, and that is a deliberate trade-off: observing
 * a real {@code OrtSession.SessionOptions} close needs the ONNX Runtime native library loaded and a
 * loadable graph, and an injected factory cannot help because {@code SessionOptions} has no
 * observable closed state and cannot be constructed without the native library. Extracting a seam
 * around it would add production structure that no test could exercise, so the leak-prone lines are
 * kept small and pinned here instead.</p>
 */
class PocketModelRuntimeTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/com/rheinmetal/tianshu/function/echo/synthesis/pocket/PocketModelRuntime.java");

    @Test
    void everySessionOptionsIsCreatedAsATryWithResourcesResource() throws Exception {
        List<String> constructions = linesContaining("new OrtSession.SessionOptions()");

        assertFalse(constructions.isEmpty(), "the runtime is expected to create SessionOptions for its sessions");
        for (String line : constructions) {
            assertTrue(line.contains("try ("),
                    "SessionOptions must be a try-with-resources resource so it is closed as soon as the "
                            + "session exists, including on the failure path: " + line.trim());
        }
    }

    @Test
    void initializationIsGuardedByAnExplicitCompletedFlag() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertFalse(source.contains("!sessions.isEmpty()"),
                "a non-empty session map does not mean the runtime is initialized; a half-finished load "
                        + "would answer the next initialize() successfully");
        assertTrue(source.contains("if (initialized) {"), "the early return has to test the completed flag");
        assertEquals(1, occurrences(source, "initialized = true"),
                "the flag may only be raised once, after every resource is in place");
        assertTrue(source.contains("initialized = false;"),
                "close() must reset the flag, otherwise a closed runtime would answer initialize() without loading");
    }

    private static List<String> linesContaining(String needle) throws Exception {
        return Files.readAllLines(SOURCE, StandardCharsets.UTF_8).stream()
                .filter(line -> line.contains(needle))
                .toList();
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
