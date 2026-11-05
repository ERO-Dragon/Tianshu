package com.rheinmetal.tianshu.mc.event;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The controls-screen category, as 1.21.9 expresses it.
 *
 * <p>Up to 1.21.8 a mapping named its category with a translation key and the game translated it. 1.21.9 names a
 * category with a resource id and derives the label from that id ({@code key.category.<namespace>.<path>}), so the two
 * things that can silently go wrong are structural: a key that builds its own category object gets a second row in the
 * controls screen (the game rejects a duplicate registration, and nothing shares the instance), and a category id
 * whose derived label key is missing from the language shards renders as a raw key.</p>
 *
 * <p>This test pins both. The platform keeps speaking translation keys — the api's declaration and the host's own
 * keys have not changed — and {@link KeyCategories} is the one place that turns one into a registered category, so
 * every mapping goes through it and the derived label exists in both languages.</p>
 */
final class KeyCategoryBoundaryTest {

    private static final String EVENT = "../1.21.11-common/src/main/java/com/rheinmetal/tianshu/mc/event";
    private static final String HOST_KEYS = EVENT + "/HostKeys.java";
    private static final String DECLARED = EVENT + "/DeclaredKeyBindings.java";
    private static final String CATEGORIES = EVENT + "/KeyCategories.java";
    /** The label 1.21.9 derives for {@code tianshu:keys}, the id derived from {@code key.categories.tianshu}. */
    private static final String DERIVED_LABEL = "key.category.tianshu.keys";

    @Test
    void everyMappingGetsItsCategoryFromTheOneRegistry() throws Exception {
        String hostKeys = read(HOST_KEYS);
        assertTrue(hostKeys.contains("private static final String CATEGORY = \"key.categories.tianshu\""),
                "the host still names its category by the translation key the api speaks");
        assertEquals(2, occurrences(hostKeys, "KeyCategories.of(CATEGORY)"),
                "both host keys take the shared category instance instead of building one");

        String declared = read(DECLARED);
        assertTrue(declared.contains("KeyCategories.of(entry.categoryLangKey())"),
                "a module's declared key resolves through the same registry");
    }

    /**
     * The derivation is the contract the label depends on: the last segment of the declared key names the id, a fixed
     * {@code keys} path fills the rest, and a declaration without a usable segment falls back to the platform's own
     * category rather than crashing the controls screen.
     */
    @Test
    void theRegisteredIdIsDerivedFromTheDeclaredKey() throws Exception {
        String categories = read(CATEGORIES);

        assertTrue(categories.contains("key.lastIndexOf('.')"),
                "the id comes from the declared key's last segment");
        assertTrue(categories.contains("Identifier.fromNamespaceAndPath(namespace, \"keys\")"),
                "the path is the fixed one the derived label is written for");
        assertTrue(categories.contains("FALLBACK"),
                "a declaration with no usable segment still groups under the platform");
        assertTrue(categories.contains("KeyMapping.Category.register(id)"),
                "the category is registered, so the controls screen orders it with the game's own");
        assertTrue(categories.contains("new KeyMapping.Category(id)"),
                "a registration the platform does not own still yields a usable category");
    }

    @Test
    void theDerivedLabelExistsInBothLanguages() throws Exception {
        for (String language : new String[]{"en_us", "zh_cn"}) {
            String shard = read("../../lang/" + language + ".json");
            assertTrue(shard.contains("\"" + DERIVED_LABEL + "\""),
                    () -> "the controls screen would show the raw key in " + language);
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
