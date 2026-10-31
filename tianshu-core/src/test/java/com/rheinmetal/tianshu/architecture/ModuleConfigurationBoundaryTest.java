package com.rheinmetal.tianshu.architecture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleConfigurationBoundaryTest {
    /**
     * Feature sources live beside the kernel, not inside it. The old root pointed at tianshu-core's own
     * {@code src/main/java}, so every scan below read an empty tree and the gate could never fail.
     */
    private static final Path REPO_ROOT = Path.of("..");
    private static final Map<String, Path> FUNCTION_SOURCES = functionSources();
    private static final Path NEOFORGE_SOURCES = REPO_ROOT.resolve(
            "tianshu-adapter/1.21.4-neoforge/src/main/java");

    @Test
    void functionModulesMustNotDependOnAggregateHostConfiguration() throws Exception {
        List<String> violations = FUNCTION_SOURCES.values().stream()
                .flatMap(root -> violations(root, "ITianshuConfig").stream())
                .toList();

        assertTrue(
                violations.isEmpty(),
                () -> "function modules must receive bounded configuration ports, not ITianshuConfig:\n"
                        + String.join("\n", violations)
        );
    }

    @Test
    void modulesMustNotDependOnAnotherModulesConfigurationPort() throws Exception {
        assertNoConfigurationDependency("tianshu-intake",
                "function.weaving.settings.WeavingConfiguration",
                "function.echo.settings.EchoConfiguration",
                "function.auxilium.storage.AuxiliumStorageConfiguration");
        assertNoConfigurationDependency("tianshu-weaving",
                "function.intake.settings.IntakeConfiguration",
                "function.echo.settings.EchoConfiguration",
                "function.auxilium.storage.AuxiliumStorageConfiguration");
        assertNoConfigurationDependency("tianshu-echo",
                "function.intake.settings.IntakeConfiguration",
                "function.weaving.settings.WeavingConfiguration",
                "function.auxilium.storage.AuxiliumStorageConfiguration");
        assertNoConfigurationDependency("tianshu-auxilium",
                "function.intake.settings.IntakeConfiguration",
                "function.weaving.settings.WeavingConfiguration",
                "function.echo.settings.EchoConfiguration");
    }

    @Test
    void neoforgeKeepsOneTomlConfigurationOwnerPerSide() throws Exception {
        List<String> specOwners = violations(NEOFORGE_SOURCES, "new ModConfigSpec.Builder()").stream()
                .map(owner -> owner.replace('\\', '/'))
                .toList();
        String bootstrap = read(NEOFORGE_SOURCES.resolve(
                "com/rheinmetal/tianshu/neoforge/TianshuNeoForge.java"
        ));

        assertTrue(specOwners.equals(List.of(
                        "com/rheinmetal/tianshu/neoforge/config/ClientConfig.java",
                        "com/rheinmetal/tianshu/neoforge/config/ServerActionConfig.java")),
                () -> "client and server toml specs must each have exactly one owner:\n" + String.join("\n", specOwners));
        assertTrue(bootstrap.contains("registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ClientConfig.SPEC)"));
        assertTrue(bootstrap.contains("ModConfig.Type.SERVER"));
        assertTrue(bootstrap.contains("ServerActionConfig.SPEC"));
        assertTrue(bootstrap.contains("MOD_ID = \"tianshu\""));
    }

    private static void assertNoConfigurationDependency(String module, String... forbiddenTypes) throws Exception {
        Path moduleRoot = FUNCTION_SOURCES.get(module);
        assertTrue(moduleRoot != null, () -> "unknown feature module: " + module);
        for (String forbiddenType : forbiddenTypes) {
            List<String> violations = violations(moduleRoot, forbiddenType);
            assertTrue(violations.isEmpty(), () -> module + " must not depend on " + forbiddenType + ":\n"
                    + String.join("\n", violations));
        }
    }

    private static Map<String, Path> functionSources() {
        Map<String, Path> roots = new LinkedHashMap<>();
        for (String module : List.of("tianshu-intake", "tianshu-echo", "tianshu-weaving", "tianshu-auxilium")) {
            roots.put(module, REPO_ROOT.resolve(module)
                    .resolve("src/main/java/com/rheinmetal/tianshu/function"));
        }
        return Map.copyOf(roots);
    }

    private static List<String> violations(Path root, String pattern) {
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
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to scan " + root, exception);
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
