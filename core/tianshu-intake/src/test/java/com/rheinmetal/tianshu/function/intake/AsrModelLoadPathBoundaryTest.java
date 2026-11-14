package com.rheinmetal.tianshu.function.intake;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The speech model has exactly one way in.
 *
 * <p>The initial read and the hotword-triggered reload used to be two doors: the first went through the
 * native-load gate, the second read the model directly and could therefore overlap whichever model the
 * gate had admitted. They now share one entry, and this test pins that down at the level the compiler
 * cannot — a second loader call site or a second lane key would silently reintroduce the bypass while
 * still compiling.
 */
class AsrModelLoadPathBoundaryTest {
    private static final Path ASR_MODULE =
            Path.of("src/main/java/com/rheinmetal/tianshu/function/intake/AsrModule.java");

    @Test
    void theReloadUsesTheSameGatedEntryAsTheInitialLoad() throws Exception {
        assertTrue(Files.isRegularFile(ASR_MODULE), "the ASR module source must be readable");
        String source = Files.readString(ASR_MODULE, StandardCharsets.UTF_8);

        assertEquals(1, occurrences(source, "= createEngine("),
                "the model must be read from exactly one place, so every path takes the same gate");
        assertTrue(source.contains("requestEngineLoad(context)"),
                "the model is read through one gated entry only");
        assertFalse(source.contains(":engine.reload"),
                "a dedicated reload lane key would be a second, ungated door into the model");
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
