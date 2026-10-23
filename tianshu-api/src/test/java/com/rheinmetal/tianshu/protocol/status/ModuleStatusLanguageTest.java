package com.rheinmetal.tianshu.protocol.status;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every language key a module can publish has text in both languages.
 *
 * <p>A missing key is invisible: the status row renders empty or shows the raw key, the build stays
 * green, and only a player notices. Two mechanisms close that hole — the lifecycle keys are derived from
 * the module id ({@link ModuleStatusKeys}), and this test checks the derived set plus every literal the
 * sources hand to {@code ModuleStatus} against both language files.</p>
 *
 * <p>It sweeps the sources instead of keeping a list, so a new status added anywhere under
 * {@code src/main/java} is covered without anyone remembering to add it here.</p>
 */
class ModuleStatusLanguageTest {

    /** Repository root, relative to this module's test working directory. */
    private static final Path REPO = Path.of("..");
    private static final Path LANG_ROOT = Path.of("../tianshu-client/src/main/resources/assets/tianshu/lang");

    /** Presence-row keys and settings-page keys are the two families a module status can carry. */
    private static final Pattern KEY_LITERAL = Pattern.compile("\"(tianshu\\.(?:presence|gui)\\.[a-zA-Z0-9_.]+)\"");

    /**
     * Keys assembled with {@code "prefix" + suffix} at runtime, so no complete literal exists in the
     * sources for the sweep to find. The suffixes come from enums in other modules, which this module
     * cannot import; the list is the price of that and must stay short.
     */
    private static final List<String> CONCATENATED_KEYS = List.of(
            "tianshu.gui.presence.status.unavailable",
            "tianshu.presence.status.idle",
            "tianshu.presence.status.listening",
            "tianshu.presence.status.thinking",
            "tianshu.presence.status.responding",
            "tianshu.presence.status.processing_task",
            "tianshu.presence.status.loading",
            "tianshu.presence.status.degraded",
            "tianshu.presence.status.error",
            "tianshu.presence.status.reconnecting"
    );

    @Test
    void everyDerivedLifecycleKeyHasTextInBothLanguages() throws Exception {
        Set<String> zh = keys("zh_cn.json");
        Set<String> en = keys("en_us.json");

        Set<String> lifecycle = ModuleStatusKeys.lifecycleKeys();
        assertFalse(lifecycle.isEmpty(), "the lifecycle key set must not be empty");
        for (String key : lifecycle) {
            assertTrue(zh.contains(key), "missing in zh_cn: " + key);
            assertTrue(en.contains(key), "missing in en_us: " + key);
        }
    }

    @Test
    void everyKeyLiteralInTheSourcesHasTextInBothLanguages() throws Exception {
        Set<String> zh = keys("zh_cn.json");
        Set<String> en = keys("en_us.json");

        Map<String, String> literals = keyLiteralsInSources();
        assertTrue(literals.size() > 50, "the source sweep must actually find the keys: " + literals.size());

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : literals.entrySet()) {
            if (!zh.contains(entry.getKey()) || !en.contains(entry.getKey())) {
                missing.add(entry.getKey() + " (" + entry.getValue() + ")");
            }
        }
        assertTrue(missing.isEmpty(), "language keys referenced by the sources but missing from a language file: " + missing);
    }

    @Test
    void keysAssembledAtRuntimeHaveTextInBothLanguages() throws Exception {
        Set<String> zh = keys("zh_cn.json");
        Set<String> en = keys("en_us.json");

        for (String key : CONCATENATED_KEYS) {
            assertTrue(zh.contains(key), "missing in zh_cn: " + key);
            assertTrue(en.contains(key), "missing in en_us: " + key);
        }
    }

    /** Every complete {@code "tianshu.presence…"/"tianshu.gui…"} literal with the file that holds it. */
    private static Map<String, String> keyLiteralsInSources() throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (Stream<Path> tree = Files.walk(REPO)) {
            tree.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> normalized(path).contains("/src/main/java/"))
                    .filter(path -> !normalized(path).contains("/build/"))
                    .filter(path -> !normalized(path).contains("/bin/"))
                    .filter(path -> !normalized(path).contains("/run/"))
                    .forEach(path -> collectLiterals(path, found));
        }
        return found;
    }

    private static void collectLiterals(Path path, Map<String, String> found) {
        String source;
        try {
            source = Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            return;
        }
        Matcher matcher = KEY_LITERAL.matcher(source);
        while (matcher.find()) {
            String key = matcher.group(1);
            // A trailing dot is a prefix the code concatenates onto, not a key on its own.
            if (key.endsWith(".")) {
                continue;
            }
            found.putIfAbsent(key, path.getFileName().toString());
        }
    }

    private static Set<String> keys(String fileName) throws Exception {
        Path file = LANG_ROOT.resolve(fileName);
        assertTrue(Files.isRegularFile(file), "missing language file: " + file);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            assertTrue(parsed.isJsonObject(), "language file is not a JSON object: " + file);
            JsonObject json = parsed.getAsJsonObject();
            Set<String> keys = new TreeSet<>();
            json.keySet().forEach(keys::add);
            return keys;
        }
    }

    private static String normalized(Path path) {
        return path.toString().replace('\\', '/');
    }
}
