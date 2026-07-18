package com.rheinmetal.tianshu.function.tts.synthesis;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void backendClosesServiceAndGenerationAcceptsCancellationSignal() throws Exception {
        String backend = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/function/tts/synthesis/MossTtsBackend.java"),
                StandardCharsets.UTF_8
        );
        String generator = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/function/tts/synthesis/moss/MossFrameGenerator.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(backend.contains("current.close()"));
        assertTrue(generator.contains("BooleanSupplier cancellationRequested"));
        assertTrue(generator.contains("cancellation.getAsBoolean()"));
    }
}
