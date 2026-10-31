package com.rheinmetal.tianshu.neoforge.event;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The adapter writes diagnostics through the router, in stable codes, never through a host logger.
 *
 * <p>Both halves are covered: the shared layer and this loader's own sources. The shared half matters more,
 * because the same file is compiled into every loader adapter — a Chinese sentence or a {@code slf4j} call that
 * slipped into it would appear in two products at once.</p>
 */
final class AdapterDiagnosticTextBoundaryTest {
    private static final Pattern HAN_TEXT = Pattern.compile("[\\u4e00-\\u9fff]");

    @Test
    void javaDiagnosticsUseStableCodesOutsideTheTomlDefinition() throws Exception {
        List<Path> roots = List.of(
                Path.of("../1.21-1.21.1-common/src/main/java/com/rheinmetal/tianshu/mc"),
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge"));
        List<Path> sources = new ArrayList<>();
        for (Path root : roots) {
            try (var files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
            }
        }
        assertFalse(sources.isEmpty(), "no adapter sources were found to check");
        for (Path file : sources) {
            // The TOML definition file carries the comments a player reads in the config screen.
            if (file.endsWith(Path.of("config", "ClientConfig.java"))) {
                continue;
            }
            String source = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(HAN_TEXT.matcher(source).find(), () -> "Hardcoded diagnostic text in " + file);
            assertFalse(source.contains("Presence failed to"), () -> "Unstable diagnostic sentence in " + file);
            assertFalse(source.contains("org.slf4j"), () -> "Host logger dependency in " + file);
            assertFalse(source.contains("System.Logger"), () -> "Host logger dependency in " + file);
        }
    }
}
