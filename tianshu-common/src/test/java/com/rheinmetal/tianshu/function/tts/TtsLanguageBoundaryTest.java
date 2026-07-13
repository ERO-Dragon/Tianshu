package com.rheinmetal.tianshu.function.tts;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsLanguageBoundaryTest {
    private static final Pattern CJK_TEXT = Pattern.compile("[\\p{IsHan}]");

    @Test
    void commonTtsProductionDoesNotContainLocalizedPlayerText() throws Exception {
        Path root = Path.of("src/main/java/com/rheinmetal/tianshu/function/tts");
        List<String> violations;
        try (var files = Files.walk(root)) {
            violations = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return CJK_TEXT.matcher(Files.readString(path, StandardCharsets.UTF_8)).find();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .map(Path::toString)
                    .toList();
        }

        assertTrue(violations.isEmpty(), "Localized text must live in host resources: " + violations);
    }
}
