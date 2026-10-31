package com.rheinmetal.tianshu.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A feature module only exists if its loader shell announces it, on every loader it claims to support.
 *
 * <p>Since the kernel stopped naming modules, nothing in the core references them: the announcement is the
 * single path by which a module's providers reach the registry. Rename a provider, move a module, or delete
 * the wrong line and the code still compiles, the unit tests still pass, and the feature is simply absent in
 * game — every guard degrades to inert.</p>
 *
 * <p>The module jars are universal: one artifact carries both {@code META-INF/neoforge.mods.toml} and
 * {@code fabric.mod.json}, so the same file must work under two loaders. That makes drift between the two
 * shells the failure worth gating, and the fix is structural rather than a test: both shells call the
 * module's own {@code *ModuleRegistration}, which holds the one list of what the module contributes. These
 * checks confirm every module keeps that shape and that each manifest points at the shell it ships.</p>
 *
 * <p>Static, because the loader entries are only reachable by starting a game.</p>
 */
class ModuleEntrypointRegistrationTest {

    private static final Path REPOSITORY_ROOT = Path.of("..");
    private static final String FUNCTION_PACKAGE = "com/rheinmetal/tianshu/function";

    private static final Pattern MOD_ANNOTATION = Pattern.compile("@Mod\\(\"([a-z0-9_]+)\"\\)");
    private static final Pattern NEOFORGE_MOD_ID = Pattern.compile("^\\s*modId\\s*=\\s*\"([a-z0-9_]+)\"",
            Pattern.MULTILINE);
    private static final Pattern FABRIC_MOD_ID = Pattern.compile("\"id\"\\s*:\\s*\"([a-z0-9_]+)\"");
    private static final Pattern FABRIC_ENTRYPOINT = Pattern.compile("\"main\"\\s*:\\s*\\[\\s*\"([^\"]+)\"");
    private static final Pattern FABRIC_KERNEL_DEPENDENCY = Pattern.compile(
            "(?s)\"depends\"\\s*:\\s*\\{.*?\"tianshu\"\\s*:");
    private static final Pattern NEOFORGE_KERNEL_DEPENDENCY = Pattern.compile(
            "(?s)modId\\s*=\\s*\"tianshu\".*?type\\s*=\\s*\"([a-zA-Z]+)\"");

    @Test
    void everyLoaderShellDelegatesToTheModulesOwnAnnouncement() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path module : featureModules()) {
            String announcement = announcementClassNameOf(module) + ".announce()";
            for (String loader : loadersOf(module)) {
                Path shell = loaderShellOf(module, loader);
                if (!read(shell).contains(announcement)) {
                    offenders.add(shell.getFileName() + " (" + loader + ") does not call " + announcement);
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                () -> "a shell that announces nothing leaves the feature absent on that loader:\n"
                        + String.join("\n", offenders));
    }

    @Test
    void theAnnouncementActuallyRegistersAProvider() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path module : featureModules()) {
            String announcement = read(announcementOf(module));
            if (!announcement.contains("TianshuModuleProviderRegistry.register(")) {
                offenders.add(module.getFileName() + " registers no module provider");
            }
        }

        assertTrue(offenders.isEmpty(),
                () -> "both shells delegate here, so an empty announcement disables the feature twice:\n"
                        + String.join("\n", offenders));
    }

    @Test
    void theNeoForgeModAnnotationMatchesTheManifestModId() throws Exception {
        for (Path module : featureModules()) {
            if (!loadersOf(module).contains("neoforge")) {
                continue;
            }
            Path shell = loaderShellOf(module, "neoforge");
            String declared = firstMatch(MOD_ANNOTATION, read(shell));
            String manifestModId = firstMatch(NEOFORGE_MOD_ID, read(neoForgeManifestOf(module)));

            assertEquals(manifestModId, declared,
                    () -> shell + " declares a different mod id than " + neoForgeManifestOf(module));
        }
    }

    @Test
    void theFabricManifestPointsAtTheShellItShips() throws Exception {
        for (Path module : featureModules()) {
            if (!loadersOf(module).contains("fabric")) {
                continue;
            }
            Path shell = loaderShellOf(module, "fabric");
            String manifest = read(fabricManifestOf(module));
            String declaredEntrypoint = firstMatch(FABRIC_ENTRYPOINT, manifest);

            assertEquals(classNameOf(module, "fabric", shell), declaredEntrypoint,
                    () -> fabricManifestOf(module) + " names an entrypoint that is not the shipped shell");
            assertEquals(firstMatch(NEOFORGE_MOD_ID, read(neoForgeManifestOf(module))),
                    firstMatch(FABRIC_MOD_ID, manifest),
                    () -> module.getFileName() + " uses one mod id per loader; both loaders see one jar");
        }
    }

    @Test
    void everyManifestRequiresTheKernelTheModuleCannotRunWithout() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path module : featureModules()) {
            if (loadersOf(module).contains("neoforge")) {
                Matcher kernel = NEOFORGE_KERNEL_DEPENDENCY.matcher(read(neoForgeManifestOf(module)));
                if (!kernel.find()) {
                    offenders.add(module.getFileName() + " does not declare the tianshu kernel for NeoForge");
                } else if (!"required".equals(kernel.group(1))) {
                    offenders.add(module.getFileName() + " declares the NeoForge kernel as " + kernel.group(1));
                }
            }
            if (loadersOf(module).contains("fabric")
                    && !FABRIC_KERNEL_DEPENDENCY.matcher(read(fabricManifestOf(module))).find()) {
                offenders.add(module.getFileName() + " does not declare the tianshu kernel for Fabric");
            }
        }

        assertTrue(offenders.isEmpty(),
                () -> "a feature module cannot assemble itself without the kernel:\n"
                        + String.join("\n", offenders));
    }

    @Test
    void theScanFindsBothLoaders() throws Exception {
        List<Path> modules = featureModules();
        assertFalse(modules.isEmpty(), "the check above is vacuous if the module scan found nothing");
        assertTrue(modules.stream().allMatch(module -> loadersOf(module).size() == 2),
                "every feature module ships both shells; a missing one is a loader silently unsupported");
    }

    /**
     * The development runs are assembled from source sets, and a feature declared there without its entry
     * output is a mod with no {@code @Mod} class: it appears in the mod list, no constructor ever runs,
     * nothing registers, and its settings page never appears. Production jars carry the entry classes, so
     * this only bites {@code runClient} — which is exactly where the registration path gets exercised by hand.
     */
    @Test
    void theDevelopmentRunDeclaresEveryEntrySourceSet() throws Exception {
        String adapterBuild = read(REPOSITORY_ROOT.resolve("tianshu-adapter/1.21-1.21.1-neoforge/build.gradle"));

        List<String> offenders = new ArrayList<>();
        for (Path module : featureModules()) {
            if (!Files.isDirectory(module.resolve("src/neoforgeEntry"))) {
                continue;
            }
            String expected = "project(':" + module.getFileName() + "').sourceSets.neoforgeEntry";
            if (!adapterBuild.contains(expected)) {
                offenders.add(module.getFileName() + " is declared for development without " + expected);
            }
        }

        assertTrue(offenders.isEmpty(),
                () -> "a feature mod declared without its entry source set is listed in game but never "
                        + "registers, so its module and settings page are missing:\n"
                        + String.join("\n", offenders));
    }

    // ---------------------------------------------------------------- discovery

    /**
     * Discovered rather than listed: a module added tomorrow is covered the moment it ships a loader shell,
     * and this test does not need editing.
     */
    private static List<Path> featureModules() throws IOException {
        try (Stream<Path> children = Files.list(REPOSITORY_ROOT)) {
            return children
                    .filter(Files::isDirectory)
                    .filter(module -> module.getFileName().toString().startsWith("tianshu-"))
                    .filter(ModuleEntrypointRegistrationTest::shipsALoaderShell)
                    .sorted()
                    .toList();
        }
    }

    private static boolean shipsALoaderShell(Path module) {
        return Files.isDirectory(module.resolve("src/neoforgeEntry"))
                || Files.isDirectory(module.resolve("src/fabricEntry"));
    }

    /** The loaders this module ships a shell for, read from the source sets that exist. */
    private static List<String> loadersOf(Path module) {
        List<String> loaders = new ArrayList<>();
        if (Files.isDirectory(module.resolve("src/neoforgeEntry"))) {
            loaders.add("neoforge");
        }
        if (Files.isDirectory(module.resolve("src/fabricEntry"))) {
            loaders.add("fabric");
        }
        return loaders;
    }

    private static Path loaderShellOf(Path module, String loader) throws IOException {
        Path root = module.resolve("src/" + loader + "Entry");
        String suffix = "neoforge".equals(loader) ? "NeoForgeMod.java" : "FabricMod.java";
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(module.getFileName() + " ships a " + loader
                            + " source set but no *" + suffix.replace(".java", "") + " inside it"));
        }
    }

    private static Path announcementOf(Path module) {
        String feature = featureNameOf(module);
        return module.resolve("src/main/java/" + FUNCTION_PACKAGE + "/" + feature + "/"
                + announcementClassNameOf(module) + ".java");
    }

    /** {@code tianshu-echo} → {@code echo}; the module directory names the feature package it owns. */
    private static String featureNameOf(Path module) {
        return module.getFileName().toString().substring("tianshu-".length());
    }

    private static String announcementClassNameOf(Path module) {
        String feature = featureNameOf(module);
        return Character.toUpperCase(feature.charAt(0)) + feature.substring(1) + "ModuleRegistration";
    }

    /**
     * The binary name of a shell, derived from where it sits rather than from its declared package: the
     * source root is {@code src/<loader>Entry/java}, so the package starts below {@code java}.
     */
    private static String classNameOf(Path module, String loader, Path shell) {
        Path root = module.resolve("src/" + loader + "Entry/java");
        String relative = root.relativize(shell).toString().replace('\\', '/').replace(".java", "");
        return relative.replace('/', '.');
    }

    private static Path neoForgeManifestOf(Path module) {
        return module.resolve("src/main/resources/META-INF/neoforge.mods.toml");
    }

    private static Path fabricManifestOf(Path module) {
        return module.resolve("src/main/resources/fabric.mod.json");
    }

    private static String firstMatch(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        assertTrue(matcher.find(), () -> "expected " + pattern + " in "
                + content.substring(0, Math.min(200, content.length())));
        return matcher.group(1);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
