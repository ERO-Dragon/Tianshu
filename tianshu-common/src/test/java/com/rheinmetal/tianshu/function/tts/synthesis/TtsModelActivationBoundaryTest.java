package com.rheinmetal.tianshu.function.tts.synthesis;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class TtsModelActivationBoundaryTest {
    @Test
    void backendConfigurationUsesResolvedModelInsteadOfPersistentSelection() throws Exception {
        Path source = Path.of("src/main/java/com/rheinmetal/tianshu/function/tts/synthesis/SherpaOnnxTtsConfigFactory.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(text.contains("getTtsModelPath"));
        assertFalse(text.contains("TtsConfiguration"));
    }
}
