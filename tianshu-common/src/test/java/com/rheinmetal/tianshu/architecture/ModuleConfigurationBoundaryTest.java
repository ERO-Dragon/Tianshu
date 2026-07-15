package com.rheinmetal.tianshu.architecture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleConfigurationBoundaryTest {
    private static final Path COMMON_SOURCES = Path.of("src/main/java");
    private static final Path FUNCTION_SOURCES = COMMON_SOURCES.resolve("com/rheinmetal/tianshu/function");
    private static final Path NEOFORGE_SOURCES = Path.of("../tianshu-neoforge/src/main/java");

    @Test
    void functionModulesMustNotDependOnAggregateHostConfiguration() throws Exception {
        List<String> violations = sourcesContaining(FUNCTION_SOURCES, "ITianshuConfig");

        assertTrue(
                violations.isEmpty(),
                () -> "function modules must receive bounded configuration ports, not ITianshuConfig:\n"
                        + String.join("\n", violations)
        );
    }

    @Test
    void modulesMustNotDependOnAnotherModulesConfigurationPort() throws Exception {
        assertNoConfigurationDependency("asr", "function.llm.settings.LlmConfiguration", "function.tts.settings.TtsConfiguration", "auxilium.storage.AXStorageConfiguration");
        assertNoConfigurationDependency("llm", "function.asr.settings.AsrConfiguration", "function.tts.settings.TtsConfiguration", "auxilium.storage.AXStorageConfiguration");
        assertNoConfigurationDependency("tts", "function.asr.settings.AsrConfiguration", "function.llm.settings.LlmConfiguration", "auxilium.storage.AXStorageConfiguration");
        assertNoConfigurationDependency("auxilium", "function.asr.settings.AsrConfiguration", "function.llm.settings.LlmConfiguration", "function.tts.settings.TtsConfiguration");
    }

    @Test
    void neoforgeKeepsOneClientTomlConfigurationOwner() throws Exception {
        List<String> specOwners = sourcesContaining(NEOFORGE_SOURCES, "new ModConfigSpec.Builder()");
        String bootstrap = read(NEOFORGE_SOURCES.resolve(
                "com/rheinmetal/tianshu/neoforge/TianshuNeoForge.java"
        ));

        assertTrue(specOwners.equals(List.of("com\\rheinmetal\\tianshu\\neoforge\\config\\ClientConfig.java"))
                        || specOwners.equals(List.of("com/rheinmetal/tianshu/neoforge/config/ClientConfig.java")),
                () -> "only ClientConfig may own the tianshu-client.toml spec:\n" + String.join("\n", specOwners));
        assertTrue(bootstrap.contains("registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ClientConfig.SPEC)"));
        assertTrue(bootstrap.contains("MOD_ID = \"tianshu\""));
    }

    private static void assertNoConfigurationDependency(String module, String... forbiddenTypes) throws Exception {
        Path moduleRoot = FUNCTION_SOURCES.resolve(module);
        for (String forbiddenType : forbiddenTypes) {
            List<String> violations = sourcesContaining(moduleRoot, forbiddenType);
            assertTrue(violations.isEmpty(), () -> module + " must not depend on " + forbiddenType + ":\n"
                    + String.join("\n", violations));
        }
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
