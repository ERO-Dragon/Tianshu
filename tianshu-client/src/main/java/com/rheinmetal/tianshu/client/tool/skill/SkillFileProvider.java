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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads skills written in the common {@code SKILL.md} format: a front-matter block with {@code name} and
 * {@code description}, a body, and optional files under the skill directory (typically {@code references/}).
 *
 * <p>Progressive disclosure is the point: the catalogue only receives the name and description; the body and
 * references are read when asked for. One provider owns one namespace (for example {@code user}) and one directory;
 * a skill id is {@code namespace:name}, so two mods can ship a skill with the same short name without colliding.</p>
 *
 * <p>The provider is intentionally file-system only: no Minecraft types, no network. A platform installs it with
 * the game's skill directory; tests install it with a temporary directory.</p>
 */
public final class SkillFileProvider {

    /** The common skill name rule: lowercase words joined by hyphens, at most 64 characters. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final int MAX_NAME = 64;
    private static final int MAX_DESCRIPTION = 1024;
    private static final int MAX_BODY_BYTES = 512 * 1024;
    private static final int PAGE_CHARS = 6_000;

    private final String namespace;
    private final Path root;
    private final Map<CapabilityId, Loaded> loaded = new ConcurrentHashMap<>();
    private final List<String> problems = new ArrayList<>();

    private record Loaded(String name, String description, Path directory, Path skillFile) {
    }

    private SkillFileProvider(String namespace, Path root) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /** Scans {@code root/<skill>/SKILL.md} and publishes each valid skill under {@code namespace}. */
    public static SkillFileProvider install(String namespace, Path root) {
        SkillFileProvider provider = new SkillFileProvider(namespace, root);
        provider.reload();
        return provider;
    }

    public String namespace() {
        return namespace;
    }

    public Path root() {
        return root;
    }

    /** Conflicts and malformed files found by the last scan; empty when everything loaded cleanly. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** Re-reads the directory. A reload replaces the provider's own entries, so a removed skill disappears. */
    public synchronized void reload() {
        unload();
        problems.clear();
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> skillFiles;
        try (Stream<Path> paths = Files.list(root)) {
            skillFiles = paths
                    .filter(Files::isDirectory)
                    .map(directory -> directory.resolve("SKILL.md"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException failure) {
            problems.add("cannot list " + root + ": " + failure.getMessage());
            return;
        }
        for (Path skillFile : skillFiles) {
            load(skillFile);
        }
    }

    /** Removes every entry this provider published. Safe to call when nothing was loaded. */
    public synchronized void unload() {
        for (CapabilityId id : loaded.keySet()) {
            CapabilityCatalog.unregister(id);
        }
        loaded.clear();
    }

    private void load(Path skillFile) {
        Path directory = skillFile.getParent();
        String directoryName = directory.getFileName().toString();
        try {
            String text = Files.readString(skillFile, StandardCharsets.UTF_8);
            FrontMatter front = FrontMatter.parse(text);
            String name = front.get("name");
            String description = front.get("description");
            if (name == null || name.isBlank()) {
                problems.add(skillFile + ": front matter has no name");
                return;
            }
            if (!NAME.matcher(name).matches() || name.length() > MAX_NAME) {
                problems.add(skillFile + ": name must be lowercase words joined by hyphens, at most " + MAX_NAME);
                return;
            }
            if (!name.equals(directoryName)) {
                problems.add(skillFile + ": name \"" + name + "\" does not match its directory \""
                        + directoryName + "\"");
                return;
            }
            if (description == null || description.isBlank()) {
                problems.add(skillFile + ": front matter has no description");
                return;
            }
            String summary = description.strip();
            if (summary.length() > MAX_DESCRIPTION) {
                summary = summary.substring(0, MAX_DESCRIPTION);
            }
            CapabilityId id = CapabilityId.of(namespace, name);
            Loaded loadedSkill = new Loaded(name, summary, directory, skillFile);
            CapabilityDescriptor descriptor = new CapabilityDescriptor(
                    id,
                    CapabilityKind.SKILL,
                    CapabilityBinding.LOCAL,
                    summary,
                    ToolSide.CLIENT,
                    ToolPermission.EVERYONE,
                    "tianshu-skills",
                    attributes(loadedSkill, front)
            );
            CapabilityCatalog.register(descriptor, (context, args) -> read(loadedSkill, args));
            loaded.put(id, loadedSkill);
        } catch (RuntimeException | IOException failure) {
            problems.add(skillFile + ": " + failure.getMessage());
        }
    }

    private static JsonObject attributes(Loaded skill, FrontMatter front) {
        JsonObject attributes = new JsonObject();
        attributes.addProperty("name", skill.name());
        attributes.addProperty("source", "skill");
        String size = front.get("size");
        if (size != null) {
            attributes.addProperty("size", size);
        }
        return attributes;
    }

    /** Reads the body or one reference file; paging applies to both. */
    private java.util.concurrent.CompletableFuture<JsonObject> read(Loaded skill, JsonObject args) {
        try {
            String file = optionalString(args, "file");
            Path target = file == null || file.isBlank() ? skill.skillFile() : reference(skill, file);
            String text = Files.readString(target, StandardCharsets.UTF_8);
            if (text.length() > MAX_BODY_BYTES) {
                text = text.substring(0, MAX_BODY_BYTES);
            }
            if (target.equals(skill.skillFile())) {
                text = bodyOnly(text);
            }
            int page = optionalPage(args);
            String paged = page(text, page);
            return java.util.concurrent.CompletableFuture.completedFuture(envelope(paged));
        } catch (IOException | IllegalArgumentException failure) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("code", "INVALID_ARGS");
            error.addProperty("message", failure.getMessage());
            return java.util.concurrent.CompletableFuture.completedFuture(error);
        }
    }

    private static Path reference(Loaded skill, String file) {
        String normalized = file.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            throw new IllegalArgumentException("reference path must stay inside the skill: " + file);
        }
        Path base = skill.directory().toAbsolutePath().normalize();
        Path target = base.resolve(normalized).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("reference path must stay inside the skill: " + file);
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("no such reference: " + file);
        }
        String lower = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt"))) {
            throw new IllegalArgumentException("only text references are readable: " + file);
        }
        return target;
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
