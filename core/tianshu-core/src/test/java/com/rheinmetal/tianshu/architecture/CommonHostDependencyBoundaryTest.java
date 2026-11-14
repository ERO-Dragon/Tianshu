package com.rheinmetal.tianshu.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonHostDependencyBoundaryTest {
    private static final Path PRODUCTION_SOURCES = Path.of("src/main/java");
    private static final List<String> FORBIDDEN_HOST_PACKAGES = List.of(
            "net.minecraft.",
            "net.neoforged.",
            "com.rheinmetal.tianshu.client.audio.",
            "com.rheinmetal.tianshu.client.",
            "com.rheinmetal.tianshu.config.",
            "com.rheinmetal.tianshu.platform."
    );

    @Test
    void commonProductionSourcesDoNotDependOnHostImplementations() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : productionJavaSources()) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            for (String forbiddenPackage : FORBIDDEN_HOST_PACKAGES) {
                if (code.contains(forbiddenPackage)) {
                    violations.add(PRODUCTION_SOURCES.relativize(source) + " -> " + forbiddenPackage);
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "common production code must depend on host ports, not host implementations:\n"
                        + String.join("\n", violations)
        );
    }

    @Test
    void commonModulesDoNotUseExternalStaticIntegrationAccess() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : productionJavaSources()) {
            if (source.getFileName().toString().equals("TianshuIntegrationAccess.java")) {
                continue;
            }
            String code = Files.readString(source, StandardCharsets.UTF_8);
            if (code.contains("TianshuIntegrationAccess")) {
                violations.add(PRODUCTION_SOURCES.relativize(source).toString());
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "common modules must use constructor-injected ports, not external static integration access:\n"
                        + String.join("\n", violations)
        );
    }

    private static List<Path> productionJavaSources() throws IOException {
        try (var files = Files.walk(PRODUCTION_SOURCES)) {
            return files.filter(CommonHostDependencyBoundaryTest::isJavaSource).toList();
        }
    }

    private static boolean isJavaSource(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java");
    }
}
