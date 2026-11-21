package com.rheinmetal.tianshu.model;

import com.rheinmetal.tianshu.testkit.RepositoryFiles;
import com.rheinmetal.tianshu.testkit.WorkspacePaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model catalog ships with the module that owns the models, and only once.
 *
 * <p>Nothing about this is visible to the compiler: a reader that points at a path nobody ships, or a
 * catalog left behind in the kernel after its owner moved out, both compile and both leave the lane to
 * fail open — the readers answer {@code emptyList()} when the resource is missing, so a botched move looks
 * exactly like an empty catalog at runtime. The invariant is therefore pinned here, following the other
 * source-level boundary tests in this build.</p>
 *
 * <p>The reader stays in the kernel on purpose (it is shared by the owning feature module, the settings
 * page and the platform configuration, see {@code doc/architecture/资源归属清单.md}); only the data
 * follows its owner, and the path mirrors the reader's package so the kernel never names a feature
 * package.</p>
 */
class ModelCatalogOwnershipTest {

    /** Repository root, relative to this module's test working directory. */

    private static final String CATALOG_ROOT = "com/rheinmetal/tianshu/model/catalog";

    @Test
    void everyCatalogShipsWithTheModuleThatOwnsIt() {
        assertShipsWith("tianshu-intake", "asr_model.json");
        assertShipsWith("tianshu-weaving", "llm_models.json");
        assertShipsWith("tianshu-weaving", "llm_embedding_models.json");
        assertShipsWith("tianshu-echo", "tts-model.json");
    }

    @Test
    void theKernelShipsNoCatalogOfItsOwn() throws Exception {
        Path resources = WorkspacePaths.find("tianshu-core/src/main/resources").orElse(null);
        if (resources == null || !Files.isDirectory(resources)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(resources)) {
            List<String> leftovers = tree.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .toList();
            assertTrue(leftovers.isEmpty(),
                    "a catalog left in tianshu-core is a second copy whose owner is no longer here: " + leftovers);
        }
    }

    @Test
    void noCatalogExistsOutsideItsOwningModule() throws Exception {
        List<String> duplicates = new ArrayList<>();
        for (Path root : WorkspacePaths.roots()) {
            for (Path path : RepositoryFiles.regularFiles(root)) {
            String normalized = normalized(path);
            if (normalized.contains("/src/main/resources/") && normalized.contains("/model/catalog/")) {
                duplicates.add(normalized);
            }
            }
        }
        assertEquals(List.of(
                        "tianshu-echo/src/main/resources/" + CATALOG_ROOT + "/tts-model.json",
                        "tianshu-intake/src/main/resources/" + CATALOG_ROOT + "/asr_model.json",
                        "tianshu-weaving/src/main/resources/" + CATALOG_ROOT + "/llm_embedding_models.json",
                        "tianshu-weaving/src/main/resources/" + CATALOG_ROOT + "/llm_models.json"
                ).stream().sorted().toList(),
                duplicates.stream().sorted().toList(),
                "exactly one copy of each catalog, in the module that owns it");
    }

    @Test
    void everyReaderNamesTheOwnedPath() throws Exception {
        assertReaderNames("tianshu-intake/src/main/java/com/rheinmetal/tianshu/intake/model/AsrModelManager.java",
                "/com/rheinmetal/tianshu/model/catalog/asr_model.json");
        assertReaderNames("tianshu-weaving/src/main/java/com/rheinmetal/tianshu/weaving/model/LlmModelManager.java",
                "/com/rheinmetal/tianshu/model/catalog/llm_models.json");
        assertReaderNames("tianshu-weaving/src/main/java/com/rheinmetal/tianshu/weaving/model/LlmModelManager.java",
                "/com/rheinmetal/tianshu/model/catalog/llm_embedding_models.json");
        assertReaderNames("tianshu-echo/src/main/java/com/rheinmetal/tianshu/echo/model/TtsModelInfo.java",
                "/com/rheinmetal/tianshu/model/catalog/tts-model.json");
    }

    @Test
    void theIntentKeywordTableShipsWithTheModuleThatReadsIt() throws Exception {
        Path owned = WorkspacePaths.require(
                "tianshu-intake/src/main/resources/com/rheinmetal/tianshu/intake/repair/ir_intent_keywords.json");
        assertTrue(Files.isRegularFile(owned), "the intent keyword table must ship beside its loader: " + owned);

        Path platformAsset = WorkspacePaths.find("tianshu-client/src/main/resources/assets/tianshu/ir-intent-keywords.json").orElse(null);
        assertFalse(platformAsset != null && Files.exists(platformAsset),
                "the table must not stay in the client's resource-pack assets: two copies would race");

        String loader = Files.readString(WorkspacePaths.require(
                "tianshu-intake/src/main/java/com/rheinmetal/tianshu/intake/repair/core/IntentKeywordLoader.java"),
                StandardCharsets.UTF_8);
        assertTrue(loader.contains(
                        "\"/com/rheinmetal/tianshu/intake/repair/ir_intent_keywords.json\""),
                "the loader has to name the bundled file by absolute path: a relative name resolves against"
                        + " the loader's own package and reads nothing");
        assertTrue(loader.contains("getResourceAsStream("),
                "and it has to read that file itself: a table that only arrives from the platform is empty"
                        + " until the first resource reload");
    }

    private static void assertShipsWith(String module, String fileName) {
        Path owned = WorkspacePaths.require(module + "/src/main/resources/" + CATALOG_ROOT + "/" + fileName);
        assertTrue(Files.isRegularFile(owned), module + " must ship " + fileName + ": " + owned);

        Path legacy = WorkspacePaths.find("tianshu-core/src/main/resources/com/rheinmetal/tianshu/constant/" + fileName).orElse(null);
        assertFalse(legacy != null && Files.exists(legacy), "the legacy kernel copy has to be gone, not duplicated: " + legacy);
    }

    private static void assertReaderNames(String readerPath, String expectedResource) throws Exception {
        String reader = Files.readString(WorkspacePaths.require(readerPath), StandardCharsets.UTF_8);
        assertTrue(reader.contains("\"" + expectedResource + "\""),
                readerPath + " has to read its catalog from " + expectedResource
                        + ": the module ships the data, the kernel keeps the reader");
    }

    /**
     * Repository-relative form of a walked path.
     *
     * <p>The walk starts at {@link #REPO}, so every path it yields begins with the {@code ../} that took
     * us out of the module directory. Comparing raw strings would then never match the expected list,
     * which is written in repository-relative terms — the assertion would fail even with a perfect
     * layout. Relativizing here keeps the expectations readable and the comparison exact.</p>
     */
    private static String normalized(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path root : WorkspacePaths.roots()) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            if (absolute.startsWith(normalizedRoot)) {
                return normalizedRoot.relativize(absolute).toString().replace('\\', '/');
            }
        }
        return absolute.toString().replace('\\', '/');
    }
}
