package com.rheinmetal.tianshu.client.tool.skill;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityBinding;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads skills written in the common {@code SKILL.md} format: a front-matter block with {@code name} and
 * {@code description}, a body, and optional files next to it (typically {@code references/}).
 *
 * <p>Progressive disclosure is optional: a skill with only {@code SKILL.md} works exactly the same; references are
 * read only when a caller asks for one. The catalogue receives the name and description only; body and references
 * stay on disk.</p>
 *
 * <p>One provider owns one namespace and one origin. A directory origin serves the player's skill folder; the
 * classpath origin serves built-in skills. A skill id is {@code namespace:name}, so two mods can ship a skill with
 * the same short name without colliding.</p>
 */
public final class SkillFileProvider {

    /** The common skill name rule: lowercase words joined by hyphens, at most 64 characters. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final int MAX_NAME = 64;
    private static final int MAX_DESCRIPTION = 1024;
    private static final int MAX_TEXT_BYTES = 512 * 1024;
    private static final int PAGE_CHARS = 6_000;

    private final String namespace;
    private final Origin origin;
    private final Map<CapabilityId, Loaded> loaded = new ConcurrentHashMap<>();
    private final List<String> problems = new ArrayList<>();

    private record Loaded(String name, String description) {
    }

    private SkillFileProvider(String namespace, Origin origin) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.origin = Objects.requireNonNull(origin, "origin");
    }

    /** Scans {@code root/<skill>/SKILL.md} and publishes each valid skill under {@code namespace}. */
    public static SkillFileProvider install(String namespace, Path root) {
        SkillFileProvider provider = new SkillFileProvider(namespace, new DirectoryOrigin(root));
        provider.reload();
        return provider;
    }

    /**
     * Publishes skills from the classpath under {@code basePath}, enumerated by {@code basePath/index.txt}
     * (one skill directory name per line).
     */
    public static SkillFileProvider installClasspath(String namespace, String basePath) {
        SkillFileProvider provider = new SkillFileProvider(namespace, new ClasspathOrigin(basePath));
        provider.reload();
        return provider;
    }

    public String namespace() {
        return namespace;
    }

    /** Conflicts and malformed files found by the last scan; empty when everything loaded cleanly. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** Re-reads the origin. A reload replaces the provider's own entries, so a removed skill disappears. */
    public synchronized void reload() {
        unload();
        problems.clear();
        List<String> names;
        try {
            names = origin.names();
        } catch (IOException failure) {
            problems.add("cannot list " + origin.describe() + ": " + failure.getMessage());
            return;
        }
        for (String directoryName : names) {
            load(directoryName);
        }
    }

    /** Removes every entry this provider published. Safe to call when nothing was loaded. */
    public synchronized void unload() {
        for (CapabilityId id : loaded.keySet()) {
            CapabilityCatalog.unregister(id);
        }
        loaded.clear();
    }

    private void load(String directoryName) {
        try {
            String text = origin.read(directoryName, "SKILL.md");
            FrontMatter front = FrontMatter.parse(text);
            String name = front.get("name");
            String description = front.get("description");
            if (name == null || name.isBlank()) {
                problems.add(directoryName + "/SKILL.md: front matter has no name");
                return;
            }
            if (!NAME.matcher(name).matches() || name.length() > MAX_NAME) {
                problems.add(directoryName + "/SKILL.md: name must be lowercase words joined by hyphens, at most "
                        + MAX_NAME);
                return;
            }
            if (!name.equals(directoryName)) {
                problems.add(directoryName + "/SKILL.md: name \"" + name + "\" does not match its directory \""
                        + directoryName + "\"");
                return;
            }
            if (description == null || description.isBlank()) {
                problems.add(directoryName + "/SKILL.md: front matter has no description");
                return;
            }
            String summary = description.strip();
            if (summary.length() > MAX_DESCRIPTION) {
                summary = summary.substring(0, MAX_DESCRIPTION);
            }
            CapabilityId id = CapabilityId.of(namespace, name);
            Loaded loadedSkill = new Loaded(name, summary);
            CapabilityDescriptor descriptor = new CapabilityDescriptor(
                    id,
                    CapabilityKind.SKILL,
                    CapabilityBinding.LOCAL,
                    summary,
                    ToolSide.CLIENT,
                    ToolPermission.EVERYONE,
                    "tianshu-skills",
                    attributes(loadedSkill)
            );
            CapabilityCatalog.register(descriptor, (context, args) -> read(directoryName, loadedSkill, args));
            loaded.put(id, loadedSkill);
        } catch (IOException | RuntimeException failure) {
            problems.add(directoryName + "/SKILL.md: " + failure.getMessage());
        }
    }

    private static JsonObject attributes(Loaded skill) {
        JsonObject attributes = new JsonObject();
        attributes.addProperty("name", skill.name());
        attributes.addProperty("source", "skill");
        return attributes;
    }

    /** Reads the body or one reference file; paging applies to both. */
    private CompletableFuture<JsonObject> read(String directoryName, Loaded skill, JsonObject args) {
        try {
            String file = optionalString(args, "file");
            String relative = file == null || file.isBlank() ? "SKILL.md" : file;
            String text = origin.read(directoryName, relative);
            if (text.length() > MAX_TEXT_BYTES) {
                text = text.substring(0, MAX_TEXT_BYTES);
            }
            if ("SKILL.md".equals(relative)) {
                text = bodyOnly(text);
            }
            return CompletableFuture.completedFuture(envelope(page(text, optionalPage(args))));
        } catch (IOException | IllegalArgumentException failure) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("code", "INVALID_ARGS");
            error.addProperty("message", failure.getMessage());
            return CompletableFuture.completedFuture(error);
        }
    }

    private static String bodyOnly(String text) {
        int start = text.indexOf("---");
        if (start < 0) {
            return text;
        }
        int lineEnd = text.indexOf('\n', start);
        int close = text.indexOf("\n---", lineEnd < 0 ? start : lineEnd);
        if (close < 0) {
            return text;
        }
        int bodyStart = text.indexOf('\n', close + 1);
        return bodyStart < 0 ? "" : text.substring(bodyStart + 1);
    }

    private static String optionalString(JsonObject args, String key) {
        return args != null && args.has(key) && args.get(key).isJsonPrimitive()
                ? args.get(key).getAsString() : null;
    }

    private static int optionalPage(JsonObject args) {
        if (args == null || !args.has("page") || !args.get("page").isJsonPrimitive()) {
            return 1;
        }
        int page = args.get("page").getAsInt();
        if (page < 1) {
            throw new IllegalArgumentException("page starts at 1");
        }
        return page;
    }

    private static String page(String text, int page) {
        int pages = Math.max(1, (text.length() + PAGE_CHARS - 1) / PAGE_CHARS);
        if (page > pages) {
            throw new IllegalArgumentException("page " + page + " does not exist; the text has " + pages);
        }
        int start = (page - 1) * PAGE_CHARS;
        int end = Math.min(text.length(), start + PAGE_CHARS);
        String slice = text.substring(start, end);
        if (pages == 1) {
            return slice;
        }
        return slice + "\n\n[page " + page + "/" + pages
                + (page < pages ? " — next: page=" + (page + 1) : " — end") + "]";
    }

    private static JsonObject envelope(String text) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ok", true);
        envelope.addProperty("text", text);
        return envelope;
    }

    /** Where skill files come from: a directory or the classpath. */
    private interface Origin {

        String describe();

        List<String> names() throws IOException;

        String read(String skill, String relative) throws IOException;
    }

    private static final class DirectoryOrigin implements Origin {
        private final Path root;

        DirectoryOrigin(Path root) {
            this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        }

        @Override
        public String describe() {
            return root.toString();
        }

        @Override
        public List<String> names() throws IOException {
            if (!Files.isDirectory(root)) {
                return List.of();
            }
            try (Stream<Path> paths = Files.list(root)) {
                return paths
                        .filter(Files::isDirectory)
                        .map(directory -> directory.resolve("SKILL.md"))
                        .filter(Files::isRegularFile)
                        .map(file -> file.getParent().getFileName().toString())
                        .sorted(Comparator.naturalOrder())
                        .toList();
            }
        }

        @Override
        public String read(String skill, String relative) throws IOException {
            validateRelative(relative);
            Path base = root.resolve(skill).normalize();
            if (!base.startsWith(root)) {
                throw new IllegalArgumentException("skill path must stay inside the root: " + skill);
            }
            Path target = base.resolve(relative).normalize();
            if (!target.startsWith(base)) {
                throw new IllegalArgumentException("file must stay inside the skill: " + relative);
            }
            if (!Files.isRegularFile(target)) {
                throw new IllegalArgumentException("no such file: " + relative);
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        }
    }

    private static final class ClasspathOrigin implements Origin {
        private final String base;
        private final ClassLoader loader = SkillFileProvider.class.getClassLoader();

        ClasspathOrigin(String base) {
            this.base = Objects.requireNonNull(base, "base");
        }

        @Override
        public String describe() {
            return "classpath:" + base;
        }

        @Override
        public List<String> names() throws IOException {
            try (InputStream index = loader.getResourceAsStream(base + "/index.txt")) {
                if (index == null) {
                    return List.of();
                }
                String text = new String(index.readAllBytes(), StandardCharsets.UTF_8);
                return text.lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .toList();
            }
        }

        @Override
        public String read(String skill, String relative) throws IOException {
            validateRelative(relative);
            String resource = base + "/" + skill + "/" + relative;
            try (InputStream input = loader.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalArgumentException("no such file: " + relative);
                }
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static void validateRelative(String relative) {
        String normalized = relative.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            throw new IllegalArgumentException("file must stay inside the skill: " + relative);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        boolean text = lower.equals("skill.md") || lower.endsWith(".md") || lower.endsWith(".markdown")
                || lower.endsWith(".txt");
        if (!text) {
            throw new IllegalArgumentException("only text files are readable: " + relative);
        }
    }

    /** The two standard keys plus unknown keys preserved; no YAML dependency is pulled into the mod. */
    private record FrontMatter(Map<String, String> values) {

        String get(String key) {
            return values.get(key);
        }

        static FrontMatter parse(String text) {
            Map<String, String> values = new java.util.LinkedHashMap<>();
            String[] lines = text.split("\n", -1);
            if (lines.length == 0 || !"---".equals(lines[0].strip())) {
                throw new IllegalArgumentException("SKILL.md must start with a --- front matter block");
            }
            for (int index = 1; index < lines.length; index++) {
                String line = lines[index];
                if ("---".equals(line.strip())) {
                    return new FrontMatter(values);
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = line.substring(0, colon).strip();
                String value = line.substring(colon + 1).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
            throw new IllegalArgumentException("SKILL.md front matter is not closed with ---");
        }
    }
}