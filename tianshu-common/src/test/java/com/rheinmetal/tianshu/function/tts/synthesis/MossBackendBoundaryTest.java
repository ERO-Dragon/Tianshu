package com.rheinmetal.tianshu.function.tts.synthesis;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class MossBackendBoundaryTest {
    @Test
    void concreteMossInferenceLivesInsideTtsSynthesisDomain() throws Exception {
        Path legacyDirectory = Path.of("src/main/java/com/rheinmetal/tianshu/model/tts/moss");
        Path backend = Path.of("src/main/java/com/rheinmetal/tianshu/function/tts/synthesis/MossTtsBackend.java");

        if (Files.isDirectory(legacyDirectory)) {
            try (var files = Files.list(legacyDirectory)) {
                assertFalse(files.anyMatch(path -> path.toString().endsWith(".java")));
            }
        }
        assertFalse(Files.readString(backend, StandardCharsets.UTF_8).contains("com.rheinmetal.tianshu.model.tts.moss"));
    }
}
