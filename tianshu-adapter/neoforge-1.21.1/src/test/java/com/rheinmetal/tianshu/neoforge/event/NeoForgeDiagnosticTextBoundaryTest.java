package com.rheinmetal.tianshu.neoforge.event;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class NeoForgeDiagnosticTextBoundaryTest {
    private static final Pattern HAN_TEXT = Pattern.compile("[\\u4e00-\\u9fff]");

    @Test
    void javaDiagnosticsUseStableCodesOutsideTheTomlDefinition() throws Exception {
        Path root = Path.of("src/main/java/com/rheinmetal/tianshu/neoforge");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.endsWith(Path.of("config", "ClientConfig.java"))) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                assertFalse(HAN_TEXT.matcher(source).find(), () -> "Hardcoded diagnostic text in " + file);
                assertFalse(source.contains("Presence failed to"), () -> "Unstable diagnostic sentence in " + file);
                assertFalse(source.contains("org.slf4j"), () -> "NeoForge host logger dependency in " + file);
                assertFalse(source.contains("System.Logger"), () -> "NeoForge host logger dependency in " + file);
            }
        }
    }
}
