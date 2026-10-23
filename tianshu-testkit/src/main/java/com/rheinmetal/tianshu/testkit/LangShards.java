package com.rheinmetal.tianshu.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The per-module language shards, read the way the build's {@code mergeLang} task reads them.
 *
 * <p>The runtime ships one {@code assets/tianshu/lang/<language>.json}, but the keys are owned per module
 * under {@code src/main/lang/} (plus the repository-level {@code lang/} for keys no module owns). A test that
 * read the shipped file would be reading build output, so the language tests read the shards instead: the
 * same input set the merge uses, one step earlier.</p>
 *
 * <p>Parsing is deliberately small and dependency-free — a shard is one flat {@code "key": "value"} entry per
 * line, and the assertions need the key sets, not the values. A repeated key is reported instead of being
 * collapsed, because collapsing is exactly the failure mode the split exists to make visible.</p>
 */
public final class LangShards {

    public static final String ZH_CN = "zh_cn";
    public static final String EN_US = "en_us";

    private static final Pattern KEY_LINE = Pattern.compile("^\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:");

    private LangShards() {
    }

    /** Every shard file that contributes {@code language}, in a deterministic order. */
    public static List<Path> files(String language) {
        Path root = repositoryRoot();
        List<Path> found = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(root)) {
            tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(language + ".json"))
                    .filter(path -> isShard(root, path))
                    .forEach(found::add);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read the language shards under " + root, failure);
        }
        found.sort((left, right) -> left.toString().compareTo(right.toString()));
        if (found.isEmpty()) {
            throw new IllegalStateException("no language shard found for " + language + " under " + root);
        }
        return found;
    }

    /**
     * The merged key set of {@code language}, across every shard.
     *
     * @throws IllegalStateException when one key is defined by two shards, or twice inside one — the build
     *                               refuses the same thing, and a test that stayed silent here would be
     *                               describing a file the build will not produce
     */
    public static Set<String> keys(String language) {
        Map<String, Path> keys = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (Path file : files(language)) {
            for (String key : keysOf(file)) {
                Path previous = keys.putIfAbsent(key, file);
                if (previous != null) {
                    duplicates.add(key + " in " + previous.getFileName() + " and " + file.getFileName());
                }
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException("duplicate language keys in the " + language + " shards: " + duplicates);
        }
        return keys.keySet();
    }

    /** The repository root, found by walking up from the working directory to {@code settings.gradle}. */
    public static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 8 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("no settings.gradle above " + Path.of("").toAbsolutePath());
    }

    /** The keys of one shard file, in file order; a key repeated in that file is rejected. */
    private static List<String> keysOf(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read language shard " + file, failure);
        }
        List<String> keys = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = KEY_LINE.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String key = matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
            if (keys.contains(key)) {
                throw new IllegalStateException("key '" + key + "' is repeated inside " + file);
            }
            keys.add(key);
        }
        return keys;
    }

    private static boolean isShard(Path root, Path path) {
        String normalized = path.toString().replace('\\', '/');
        if (normalized.contains("/build/") || normalized.contains("/bin/") || normalized.contains("/run/")
                || normalized.contains("/.git/")) {
            return false;
        }
        if (path.getParent() != null && path.getParent().equals(root.resolve("lang"))) {
            return true;
        }
        return normalized.contains("/src/main/lang/");
    }
}
