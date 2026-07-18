package com.rheinmetal.tianshu.architecture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class CommonDiagnosticTextBoundaryTest {
    @Test
    void functionModuleEnvironmentLogsDoNotEmbedLocalizedSentences() throws Exception {
        Path root = resolveFromWorkspace(Path.of("tianshu-common/src/main/java/com/rheinmetal/tianshu/function"));
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                int lineNumber = 0;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    lineNumber++;
                    if (line.contains(".info(") || line.contains(".warn(") || line.contains(".error(")) {
                        assertFalse(line.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF),
                                file + ":" + lineNumber);
                    }
                }
            }
        }
    }

    private static Path resolveFromWorkspace(Path relativePath) {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            Path candidate = current.resolve(relativePath).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return relativePath;
    }
}
