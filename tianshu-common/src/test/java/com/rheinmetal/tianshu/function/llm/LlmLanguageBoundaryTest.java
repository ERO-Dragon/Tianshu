package com.rheinmetal.tianshu.function.llm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class LlmLanguageBoundaryTest {
    @Test
    void commonLlmProductionCodeContainsNoHardcodedCjkText() throws Exception {
        Path root = Path.of("src/main/java/com/rheinmetal/tianshu/function/llm");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                assertFalse(source.codePoints().anyMatch(LlmLanguageBoundaryTest::isCjk), file.toString());
            }
        }
    }

    @Test
    void commonDownloadSnapshotUsesResourceKeysInsteadOfUiText() throws Exception {
        Path sourcePath = Path.of("src/main/java/com/rheinmetal/tianshu/function/llm/LlmModelService.java");
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);

        assertFalse(source.contains("Download queue is full"));
    }

    private static boolean isCjk(int codePoint) {
        return codePoint >= 0x3400 && codePoint <= 0x9FFF;
    }
}
