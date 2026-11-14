package com.rheinmetal.tianshu.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPlatformBoundaryTest {
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "import net.minecraft.",
            "import net.neoforged.",
            "import org.lwjgl.",
            "import com.mojang.blaze3d."
    );

    @Test
    void mainSourcesDoNotImportMinecraftOrLoaderApis() throws IOException {
        Path mainSources = Path.of("src", "main", "java");
        if (Files.notExists(mainSources)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(mainSources)) {
            List<String> violations = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(ClientPlatformBoundaryTest::forbiddenImports)
                    .toList();
            assertTrue(violations.isEmpty(), () -> "Forbidden client platform imports:\n" + String.join("\n", violations));
        }
    }

    private static Stream<String> forbiddenImports(Path source) {
        try {
            return Files.readAllLines(source, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> FORBIDDEN_IMPORTS.stream().anyMatch(line::startsWith))
                    .map(line -> source + ": " + line);
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to inspect " + source, failure);
        }
    }
}
