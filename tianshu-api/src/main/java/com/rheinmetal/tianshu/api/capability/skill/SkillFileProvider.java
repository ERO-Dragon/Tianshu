package com.rheinmetal.tianshu.api.capability.skill;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityAccess;
import com.rheinmetal.tianshu.api.capability.CapabilityBinding;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;

import com.rheinmetal.tianshu.api.tool.ToolCaller;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Publishes skills from the classpath under {@code basePath}, enumerated by {@code basePath/index.txt}
 * (one directory name per line), one skill per {@code <dir>/SKILL.md} with an optional {@code references/} folder.
 *
 * <p>Progressive disclosure is optional: a skill with only {@code SKILL.md} works exactly the same; references are
 * read only when a caller asks for one. The catalogue receives the name and description only; body and references
 * stay on disk.</p>
 *
 * <p>One provider owns one namespace and one origin, and the namespace is also the publisher: a directory origin serves
 * the player's skill folder under {@code user}; the class-path origin serves the built-in skills under {@code tianshu};
 * a mod registers its own folder under its own id. A skill id is {@code namespace:name}, so two mods can ship a skill
 * with the same directory name without colliding, and "whose row is this" never needs a second lookup.</p>
 *
 * <p>A skill's {@code references/} folder is how a tool gets its document: the files are found from the tool's id through
 * {@link SkillReferences}, which is why a mod's own skill folder documents its own tools without registering anything
 * else.</p>
 */
public final class SkillFileProvider {

    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final int MAX_NAME = 64;
    private static final int MAX_TEXT_BYTES = 48_000;
    private static final String SKILL_FILE = "SKILL.md";
    private static final String REFERENCES = "references/";

    private final String namespace;
    private final String toolNamespace;
    private final Origin origin;
    private final Map<CapabilityId, Loaded> loaded = new ConcurrentHashMap<>();
    private final List<String> problems = new ArrayList<>();

    private record Loaded(String directory, String name, String description, boolean toolReferences) {
    }

    private SkillFileProvider(String namespace, String toolNamespace, Origin origin) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.toolNamespace = Objects.requireNonNull(toolNamespace, "toolNamespace");
        this.origin = Objects.requireNonNull(origin, "origin");
    }

    /** A provider over a directory of skills; {@code namespace} is the owner's id. */
    public static SkillFileProvider install(String namespace, Path root) {
        SkillFileProvider provider = new SkillFileProvider(namespace, namespace, new DirectoryOrigin(root));
        provider.reload();
        return provider;
    }

    /** A provider over skills shipped on the classpath; {@code basePath} holds {@code index.txt} and the skill folders. */
    public static SkillFileProvider installClasspath(String namespace, String toolNamespace, String basePath) {
        SkillFileProvider provider = new SkillFileProvider(namespace, toolNamespace,
                new ClasspathOrigin(basePath, SkillFileProvider.class.getClassLoader()));
        provider.reload();
        return provider;
    }

    String toolNamespace() {
        return toolNamespace;
    }

    /** Everything this provider could not index, in the order it found them. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** Unregisters every skill this provider published, and its references with them. */
    public void unload() {
        SkillReferences.withdraw(this);
        for (CapabilityId id : List.copyOf(loaded.keySet())) {
            CapabilityCatalog.unregister(id);
        }
        loaded.clear();
    }

    /** Re-reads the origin; skills that disappeared are unregistered, skills that changed are re-registered. */
    public void reload() {
        SkillReferences.publish(this);
        problems.clear();
        loaded.forEach((id, skill) -> CapabilityCatalog.unregister(id));
        loaded.clear();
        try {
            for (String directory : origin.skillDirectories()) {
                load(directory);
            }
        } catch (IOException unreadable) {
            problems.add(origin.describe() + ": " + unreadable.getMessage());
        }
    }

    private void load(String directoryName) {
        try {
            String text = origin.read(directoryName, SKILL_FILE);
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
            if (description.length() > 1024) {
                problems.add(directoryName + "/SKILL.md: description is longer than 1024 characters");
                return;
            }
            String summary = description.strip();
            CapabilityId id = CapabilityId.of(namespace, name);
            Loaded loadedSkill = new Loaded(directoryName, name, summary, "tools".equals(front.get("metadata.references")));
            CapabilityDescriptor descriptor = new CapabilityDescriptor(
                    id,
                    CapabilityKind.SKILL,
                    CapabilityBinding.LOCAL,
                    summary,
                    ToolSide.CLIENT,
                    ToolPermission.EVERYONE,
                    // The publisher is the namespace: a skill folder belongs to whoever's id it is registered under,
                    // so the player's folder is "user" and a mod's is the mod's own id. A second, provider-only name
                    // would let "who published this" and "whose id is this" drift apart.
                    namespace,
                    attributes(loadedSkill, front.get(FrontMatter.SHARE))
            );
            CapabilityCatalog.register(descriptor, (context, args) -> read(loadedSkill, context.caller(), args));
            loaded.put(id, loadedSkill);
        } catch (IOException | RuntimeException failure) {
            problems.add(directoryName + "/SKILL.md: " + failure.getMessage());
        }
    }

    private static JsonObject attributes(Loaded skill, String share) {
        JsonObject attributes = new JsonObject();
        attributes.addProperty("name", skill.name());
        attributes.addProperty("source", "skill");
        // Sharing is a declaration, not a constant: the skill itself says who may see it. Undeclared, it follows its
        // provider — the base and the player's own folder are open, a module's is private — which is CapabilitySharing's
        // default, so writing it down here would only be a second place for the same answer to go stale.
        if (share != null && !share.isBlank()) {
            attributes.addProperty("share", share.strip());
        }
        return attributes;
    }

    /**
     * The text of one reference file, when one of this provider's skills ships it.
     *
     * <p>Asked by file name rather than by tool id: this provider knows its own folders, not the catalogue's ids, and a
     * document is named after the tool it documents. Skills are searched in directory order, so two skills shipping the
     * same file resolve the same way every time instead of following hash order.</p>
     */
    Optional<SkillReferences.Document> reference(String file) {
        if (file == null || file.isBlank()) {
            return Optional.empty();
        }
        String relative = REFERENCES + file;
        List<Map.Entry<CapabilityId, Loaded>> ordered = new ArrayList<>(loaded.entrySet());
        ordered.sort(Comparator.comparing(entry -> entry.getValue().directory()));
        for (Map.Entry<CapabilityId, Loaded> entry : ordered) {
            String directory = entry.getValue().directory();
            if (!origin.exists(directory, relative)) {
                continue;
            }
            try {
                String text = origin.read(directory, relative);
                return Optional.of(new SkillReferences.Document(entry.getKey(), relative,
                        text.length() > MAX_TEXT_BYTES ? text.substring(0, MAX_TEXT_BYTES) : text));
            } catch (IOException unreadable) {
                problems.add(directory + "/" + relative + ": " + unreadable.getMessage());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Reads the body or one reference file; paging applies to both. */
    private CompletableFuture<JsonObject> read(Loaded skill, ToolCaller caller, JsonObject args) {
        try {
            String file = optionalString(args, "file");
            String relative = file == null || file.isBlank() ? SKILL_FILE : file;
            validateRelative(relative);
            relative = Path.of(relative.replace('\\', '/')).normalize().toString().replace('\\', '/');
            if (!SKILL_FILE.equals(relative) && !readableReference(skill, relative, caller)) {
                throw new IllegalArgumentException("no such file: " + relative);
            }
            String text = origin.read(skill.directory(), relative);
            if (text.length() > MAX_TEXT_BYTES) {
                text = text.substring(0, MAX_TEXT_BYTES);
            }
            if (SKILL_FILE.equals(relative)) {
                text = FrontMatter.body(text);
            }
            return CompletableFuture.completedFuture(envelope(TextPages.page(text, optionalPage(args),
                    TextPages.DEFAULT_PAGE_CHARS)));
        } catch (IOException | IllegalArgumentException failure) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("code", "INVALID_ARGS");
            error.addProperty("message", failure.getMessage());
            return CompletableFuture.completedFuture(error);
        }
    }

    private boolean readableReference(Loaded skill, String relative, ToolCaller caller) {
        if (!relative.startsWith(REFERENCES) || !relative.endsWith(".md")) {
            return !skill.toolReferences();
        }
        String name = relative.substring(REFERENCES.length(), relative.length() - ".md".length());
        if (name.startsWith(toolNamespace + ".")) {
            name = name.substring(toolNamespace.length() + 1);
        }
        CapabilityId id;
        try {
            id = CapabilityId.of(toolNamespace, name);
        } catch (IllegalArgumentException ordinaryFile) {
            return !skill.toolReferences();
        }
        var row = CapabilityCatalog.find(id);
        if (row.isEmpty() || !row.get().kind().isTool()) {
            return !skill.toolReferences();
        }
        return CapabilityAccess.visibleTo(row.get(), caller);
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

    private static JsonObject envelope(String text) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ok", true);
        envelope.addProperty("text", text);
        return envelope;
    }

    /** Where a skill's files come from: a directory on disk, or the classpath. */
    private interface Origin {

        String describe();

        /** Skill directory names, in the order they should be indexed. */
        List<String> skillDirectories() throws IOException;

        /** Reads one file inside a skill; must stay inside that skill's directory. */
        String read(String skill, String relative) throws IOException;

        /**
         * Whether one file inside a skill exists.
         *
         * <p>Separate from {@link #skillDirectories()} on purpose: a class loader can fetch a named resource from a jar
         * but cannot list a jar directory, so "find this tool's document" has to be a question about a known name
         * rather than a listing.</p>
         */
        boolean exists(String skill, String relative);
    }

    private static final class DirectoryOrigin implements Origin {

        private final Path root;

        private DirectoryOrigin(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        @Override
        public String describe() {
            return root.toString();
        }

        @Override
        public List<String> skillDirectories() throws IOException {
            if (!Files.isDirectory(root)) {
                return List.of();
            }
            try (Stream<Path> entries = Files.list(root)) {
                return entries
                        .filter(Files::isDirectory)
                        .filter(path -> Files.isRegularFile(path.resolve(SKILL_FILE)))
                        .map(path -> path.getFileName().toString())
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

        @Override
        public boolean exists(String skill, String relative) {
            try {
                validateRelative(relative);
                Path base = root.resolve(skill).normalize();
                if (!base.startsWith(root)) {
                    return false;
                }
                Path target = base.resolve(relative).normalize();
                return target.startsWith(base) && Files.isRegularFile(target);
            } catch (RuntimeException invalid) {
                return false;
            }
        }
    }

    private static final class ClasspathOrigin implements Origin {

        private final String base;
        private final ClassLoader loader;

        private ClasspathOrigin(String base, ClassLoader loader) {
            this.base = base;
            this.loader = loader;
        }

        @Override
        public String describe() {
            return "classpath:" + base;
        }

        @Override
        public List<String> skillDirectories() throws IOException {
            try (InputStream index = loader.getResourceAsStream(base + "/index.txt")) {
                if (index == null) {
                    throw new IOException("no index.txt under " + base);
                }
                List<String> names = new ArrayList<>();
                for (String line : new String(index.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    String value = line.strip();
                    if (!value.isEmpty() && !value.startsWith("#")) {
                        names.add(value);
                    }
                }
                return List.copyOf(names);
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

        @Override
        public boolean exists(String skill, String relative) {
            try {
                validateRelative(relative);
            } catch (RuntimeException invalid) {
                return false;
            }
            return loader.getResource(base + "/" + skill + "/" + relative) != null;
        }
    }

    private static void validateRelative(String relative) {
        String normalized = relative.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            throw new IllegalArgumentException("file must stay inside the skill: " + relative);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".class") || lower.endsWith(".jar")) {
            throw new IllegalArgumentException("not a readable skill file: " + relative);
        }
    }
}