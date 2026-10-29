package com.rheinmetal.tianshu.protocol.status;

import com.rheinmetal.tianshu.testkit.LangShards;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every language key a module can publish has text in both languages.
 *
 * <p>A missing key is invisible: the status row renders empty or shows the raw key, the build stays green,
 * and only a player notices. Two mechanisms close that hole — the lifecycle keys are derived from the module
 * id ({@link ModuleStatusKeys}), and this test checks the derived set plus every literal the sources hand to
 * {@code ModuleStatus} against both languages.</p>
 *
 * <p>The keys are read from the per-module shards ({@link LangShards}), not from the merged runtime file: the
 * merged file is build output, the shards are the source of truth the merge reads. The sweep over the sources
 * stays, so a new status added anywhere under {@code src/main/java} is covered without anyone remembering to
 * add it here.</p>
 */
class ModuleStatusLanguageTest {

    private static final Path REPO = LangShards.repositoryRoot();

    /** Module status keys and settings-page keys are the families a module row can carry. */
    private static final Pattern KEY_LITERAL = Pattern.compile("\"(tianshu\\.(?:runtime|presence|gui)\\.[a-zA-Z0-9_.]+)\"");

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
            "tianshu.presence.status.loading",
            "tianshu.presence.status.error",
            "tianshu.presence.status.reconnecting"
    );

    @Test
    void everyDerivedLifecycleKeyHasTextInBothLanguages() {
        Set<String> zh = LangShards.keys(LangShards.ZH_CN);
        Set<String> en = LangShards.keys(LangShards.EN_US);

        Set<String> lifecycle = ModuleStatusKeys.lifecycleKeys();
        assertFalse(lifecycle.isEmpty(), "the lifecycle key set must not be empty");
        for (String key : lifecycle) {
            assertTrue(zh.contains(key), "missing in zh_cn: " + key);
            assertTrue(en.contains(key), "missing in en_us: " + key);
        }
    }

    @Test
    void everyKeyLiteralInTheSourcesHasTextInBothLanguages() throws Exception {
        Set<String> zh = LangShards.keys(LangShards.ZH_CN);
        Set<String> en = LangShards.keys(LangShards.EN_US);

        Map<String, String> literals = keyLiteralsInSources();
        assertTrue(literals.size() > 50, "the source sweep must actually find the keys: " + literals.size());

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : literals.entrySet()) {
            if (!zh.contains(entry.getKey()) || !en.contains(entry.getKey())) {
                missing.add(entry.getKey() + " (" + entry.getValue() + ")");
            }
        }
        assertTrue(missing.isEmpty(), "language keys referenced by the sources but missing from a language shard: " + missing);
    }

    @Test
    void keysAssembledAtRuntimeHaveTextInBothLanguages() {
        Set<String> zh = LangShards.keys(LangShards.ZH_CN);
        Set<String> en = LangShards.keys(LangShards.EN_US);

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

    private static String normalized(Path path) {
        return path.toString().replace('\\', '/');
    }
}
