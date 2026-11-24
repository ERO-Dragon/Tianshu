package com.rheinmetal.tianshu.dubhe.agent.skill.book;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.assembly.InstalledModIds;
import com.rheinmetal.tianshu.api.assembly.ModResources;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads real packaged mod books, not fixtures.
 *
 * <p>The sample jars are not committed — downloads live in a git-ignored {@code libs/} directory — so this test
 * is skipped unless {@code DUBHE_BOOK_SAMPLES} points at one. That is deliberate: the fixtures in the other
 * tests encode what we <em>think</em> a book looks like, and this one is what catches the day reality differs —
 * which it already has twice, over where {@code book.json} lives and over how a single entry tree is
 * translated.</p>
 */
class DubheBookJarSamplesTest {

    @Test
    void readsTheEntryTreeFromOneLanguageAndTheTextFromAnother() throws IOException {
        Path directory = samples();
        List<Path> jars;
        try (Stream<Path> listing = Files.list(directory)) {
            jars = listing.filter(path -> path.toString().endsWith(".jar")).toList();
        }
        assertFalse(jars.isEmpty(), "no jars in " + directory);

        Map<String, FileSystem> systems = new LinkedHashMap<>();
        try {
            for (Path jar : jars) {
                systems.put(modId(jar), FileSystems.newFileSystem(jar));
            }
            InstalledModIds ids = () -> List.copyOf(systems.keySet());
            ModResources resources = over(systems);

            DubheBookCatalog chinese =
                    DubheBookCatalog.load(ids, resources, List.of("zh_cn", "en_us"), LogSink.SYSTEM);
            assertTrue(chinese.size() > 300, "expected real books to parse, got " + chinese.size() + " entries");

            // Three shapes, all in these samples:
            //  - Botania ships one en_us entry tree and a full lang/zh_cn.json beside it: the tree comes from
            //    the only language that has one, the text from the language the reader speaks.
            //  - Ars Nouveau pages are typed patchouli:text / patchouli:spotlight, not bare text.
            //  - Iron's Spells ships four entry trees and writes Chinese straight into them, no lang keys.
            assertHasTitle(chinese, "魔力池");
            assertHasTitle(chinese, "游戏前期");
            assertHasBody(chinese, "法术卷轴");

            assertHasTitle(DubheBookCatalog.load(ids, resources, List.of("en_us"), LogSink.SYSTEM), "Mana Pool");
        } finally {
            for (FileSystem system : systems.values()) {
                system.close();
            }
        }
    }

    private static void assertHasTitle(DubheBookCatalog catalog, String title) {
        assertTrue(catalog.search(title, 20).stream().anyMatch(entry -> entry.title().contains(title)),
                "expected an entry titled " + title);
    }

    private static void assertHasBody(DubheBookCatalog catalog, String term) {
        assertTrue(catalog.search(term, 20).stream().anyMatch(entry -> entry.text().contains(term)),
                "expected prose mentioning " + term + " — a spotligh page's text must not be dropped");
    }

    private static Path samples() {
        String configured = System.getenv("DUBHE_BOOK_SAMPLES");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "set DUBHE_BOOK_SAMPLES to a directory of mod jars");
        Path directory = Path.of(configured);
        Assumptions.assumeTrue(Files.isDirectory(directory), "no sample directory at " + directory);
        return directory;
    }

    private static String modId(Path jar) {
        String name = jar.getFileName().toString();
        return name.substring(0, name.length() - ".jar".length());
    }

    /** The same port the adapters fill, backed here by the jar's own file system. */
    private static ModResources over(Map<String, FileSystem> systems) {
        return new ModResources() {
            @Override
            public List<String> children(String modId, String directory) {
                Path path = resolve(modId, directory);
                if (path == null || !Files.isDirectory(path)) {
                    return List.of();
                }
                try (Stream<Path> entries = Files.list(path)) {
                    return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
                } catch (IOException | RuntimeException failure) {
                    return List.of();
                }
            }

            @Override
            public Optional<String> read(String modId, String path) {
                Path resolved = resolve(modId, path);
                if (resolved == null || !Files.isRegularFile(resolved)) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(Files.readString(resolved, StandardCharsets.UTF_8));
                } catch (IOException | RuntimeException failure) {
                    return Optional.empty();
                }
            }

            private Path resolve(String modId, String path) {
                FileSystem system = systems.get(modId);
                return system == null ? null : system.getPath(path);
            }
        };
    }
}