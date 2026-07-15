package com.rheinmetal.tianshu.architecture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleAssemblyRuntimeBoundaryTest {
    private static final Path COMMON_SOURCES = Path.of("src/main/java");
    private static final Path FUNCTION_SOURCES = COMMON_SOURCES.resolve("com/rheinmetal/tianshu/function");
    private static final Path NEOFORGE_SOURCES = Path.of("../tianshu-neoforge/src/main/java");

    @Test
    void assemblyContextExposesBoundedModuleRuntimeInsteadOfProtocolRuntime() throws Exception {
        String context = read(COMMON_SOURCES.resolve(
                "com/rheinmetal/tianshu/core/lifecycle/TianshuModuleAssemblyContext.java"
        ));
        String client = read(NEOFORGE_SOURCES.resolve(
                "com/rheinmetal/tianshu/client/TianshuClient.java"
        ));

        assertFalse(context.contains("ProtocolRuntime"));
        assertFalse(context.contains("protocolRuntime"));
        assertTrue(context.contains("ModuleRuntimeAccess moduleRuntime"));
        assertFalse(client.contains("context.protocolRuntime()"));
    }

    @Test
    void assemblyContextDoesNotExposeAggregateHostConfiguration() throws Exception {
        String context = read(COMMON_SOURCES.resolve(
                "com/rheinmetal/tianshu/core/lifecycle/TianshuModuleAssemblyContext.java"
        ));

        assertFalse(context.contains("ITianshuConfig"));
        assertFalse(context.contains("ITianshuConfig config"));
    }

    @Test
    void functionModulesDoNotReceiveFullProtocolRuntime() throws Exception {
        List<String> violations = sourcesContaining(FUNCTION_SOURCES, "ProtocolRuntime");

        assertTrue(
                violations.isEmpty(),
                () -> "function modules must depend on ModuleRuntimeAccess/ModuleProtocolAccess, not ProtocolRuntime:\n"
                        + String.join("\n", violations)
        );
    }

    @Test
    void functionExecutionCollaboratorsDoNotReceiveExecutorManager() throws Exception {
        List<String> violations = sourcesContaining(FUNCTION_SOURCES, "ProtocolExecutorManager");

        assertTrue(
                violations.isEmpty(),
                () -> "function modules must express task intent through ModuleExecutionAccess:\n"
                        + String.join("\n", violations)
        );
    }

    @Test
    void neoforgeAssemblyDoesNotReceiveFullProtocolRuntime() throws Exception {
        List<Path> roots = List.of(
                NEOFORGE_SOURCES.resolve("com/rheinmetal/tianshu/client/lifecycle"),
                NEOFORGE_SOURCES.resolve("com/rheinmetal/tianshu/client/presence"),
                NEOFORGE_SOURCES.resolve("com/rheinmetal/tianshu/client/ir")
        );
        List<String> violations = new ArrayList<>();
        for (Path root : roots) {
            violations.addAll(sourcesContaining(root, "ProtocolRuntime"));
        }

        assertTrue(
                violations.isEmpty(),
                () -> "NeoForge assembly code must receive bounded module runtime ports:\n"
                        + String.join("\n", violations)
        );
    }

    private static List<String> sourcesContaining(Path root, String pattern) throws Exception {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var files = Files.walk(root)) {
            return files
                    .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                    .filter(path -> read(path).contains(pattern))
                    .map(path -> root.relativize(path).toString())
                    .sorted()
                    .toList();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
