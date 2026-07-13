package com.rheinmetal.tianshu.text;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SentenceSegmenterBoundaryTest {
    @Test
    void auxiliumDoesNotDependOnTtsTextUtilities() throws Exception {
        Path source = Path.of("src/main/java/com/rheinmetal/tianshu/function/auxilium/core/output/AXSentenceBuffer.java");

        String content = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(content.contains("function.tts.text"));
    }
}
