package com.rheinmetal.tianshu.client.capability;

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
 * File-backed {@code resource} and {@code prompt} capabilities. A source supplies flat text entries; the file stem
 * is the name, and a standard front-matter block supplies {@code name} and {@code description}.
 *
 * <p>Resources are read with optional paging. Prompts are templates: {@code {{key}}} placeholders are replaced from
 * the call's {@code arguments} object; a key that is absent is left visible rather than silently blanked, so the
 * caller can see what it forgot.</p>
 *
 * <p>Two sources exist: a directory (the player's files) and the classpath (built-in content). The provider does not
 * know which one it has, so parsing, validation and rendering are identical for both.</p>
 */
public final class TextCapabilityProvider {

    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final int MAX_NAME = 64;
    private static final int MAX_DESCRIPTION = 1024;
    private static final int MAX_TEXT_BYTES = 512 * 1024;

    private final CapabilityKind kind;
    private final String namespace;
    private final Source source;
    private final Map<CapabilityId, Loaded> loaded = new ConcurrentHashMap<>();
    private final List<String> problems = new ArrayList<>();

    private record Loaded(String name, String description, TextSource text) {
    }

    /** Reads one entry's current text; called on demand, never at index time. */
    @FunctionalInterface
    public interface TextSource {
        String read() throws IOException;
    }

    /** One flat entry: its name (file stem) and how to read it. */
    public record Entry(String name, TextSource source) {
    }

    /** Where entries come from. */
    public interface Source {
        List<Entry> entries() throws IOException;

        String describe();
    }

    private TextCapabilityProvider(CapabilityKind kind, String namespace, Source source) {
        if (!kind.equals(CapabilityKind.RESOURCE) && !kind.equals(CapabilityKind.PROMPT)) {
            throw new IllegalArgumentException("text provider supports resource/prompt, not " + kind);
        }
        this.kind = kind;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.source = Objects.requireNonNull(source, "source");
    }

    public static TextCapabilityProvider install(CapabilityKind kind, String namespace, Path root) {
        TextCapabilityProvider provider = new TextCapabilityProvider(kind, namespace, new DirectorySource(root));
        provider.reload();
        return provider;
    }

    /** Built-in content enumerated by {@code base/index.txt}, one resource name per line. */
    public static TextCapabilityProvider installClasspath(CapabilityKind kind, String namespace, String base) {
        TextCapabilityProvider provider = new TextCapabilityProvider(kind, namespace,
                new ClasspathSource(base, TextCapabilityProvider.class.getClassLoader()));
        provider.reload();
        return provider;
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    public synchronized void reload() {
        unload();
        problems.clear();
        List<Entry> entries;
        try {
            entries = source.entries();
        } catch (IOException failure) {
            problems.add("cannot list " + source.describe() + ": " + failure.getMessage());
            return;
        }
        for (Entry entry : entries) {
            load(entry);
        }
    }

    public synchronized void unload() {
        for (CapabilityId id : loaded.keySet()) {
            CapabilityCatalog.unregister(id);
        }
        loaded.clear();
    }

    private void load(Entry entry) {
        String name = entry.name();
        try {
            String text = entry.source().read();
            FrontMatter front = FrontMatter.parse(text);
            String declared = front.get("name");
            String description = front.get("description");
            if (declared == null || !NAME.matcher(declared).matches() || declared.length() > MAX_NAME) {
                problems.add(name + ": name must be lowercase words joined by hyphens");
                return;
            }
            if (!declared.equals(name)) {
                problems.add(name + ": name \"" + declared + "\" does not match the entry name \"" + name + "\"");
                return;
            }
            if (description == null || description.isBlank()) {
                problems.add(name + ": front matter has no description");
                return;
            }
            String summary = description.strip();
            if (summary.length() > MAX_DESCRIPTION) {
                summary = summary.substring(0, MAX_DESCRIPTION);
            }
            CapabilityId id = CapabilityId.of(namespace, name);
            Loaded loadedCapability = new Loaded(name, summary, entry.source());
            JsonObject attributes = new JsonObject();
            attributes.addProperty("name", name);
            attributes.addProperty("source", kind.value());
            attributes.addProperty("mediaType", "text/markdown");
            CapabilityDescriptor descriptor = new CapabilityDescriptor(id, kind, CapabilityBinding.LOCAL, summary,
                    ToolSide.CLIENT, ToolPermission.EVERYONE, "tianshu-text", attributes);
            CapabilityCatalog.register(descriptor, (context, args) -> read(loadedCapability, args));
            loaded.put(id, loadedCapability);
        } catch (IOException | RuntimeException failure) {
            problems.add(name + ": " + failure.getMessage());
        }
    }

    private CompletableFuture<JsonObject> read(Loaded capability, JsonObject args) {
        try {
            String text = capability.text().read();
            if (text.length() > MAX_TEXT_BYTES) {
                text = text.substring(0, MAX_TEXT_BYTES);
            }
            String body = FrontMatter.body(text);
            String result = kind.equals(CapabilityKind.RESOURCE)
                    ? TextPages.page(body, page(args), TextPages.DEFAULT_PAGE_CHARS)
                    : render(body, args);
            JsonObject envelope = new JsonObject();
            envelope.addProperty("ok", true);
            envelope.addProperty("text", result);
            return CompletableFuture.completedFuture(envelope);
        } catch (IOException | IllegalArgumentException failure) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("code", "INVALID_ARGS");
            error.addProperty("message", failure.getMessage());
            return CompletableFuture.completedFuture(error);
        }
    }

    private static int page(JsonObject args) {
        if (args == null || !args.has("page") || !args.get("page").isJsonPrimitive()) {
            return 1;
        }
        return args.get("page").getAsInt();
    }

    private static String render(String template, JsonObject arguments) {
        if (arguments == null) {
            return template;
        }
        String rendered = template;
        for (String key : arguments.keySet()) {
            if (!arguments.get(key).isJsonPrimitive()) {
                continue;
            }
            rendered = rendered.replace("{{" + key + "}}", arguments.get(key).getAsString());
        }
        return rendered;
    }

    /** Flat directory of .md/.markdown/.txt files. */
    private static final class DirectorySource implements Source {
        private final Path root;

        DirectorySource(Path root) {
            this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        }

        @Override
        public String describe() {
            return root.toString();
        }

        @Override
        public List<Entry> entries() throws IOException {
            if (!Files.isDirectory(root)) {
                return List.of();
            }
            try (Stream<Path> paths = Files.list(root)) {
                return paths.filter(Files::isRegularFile)
                        .filter(path -> {
                            String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
                            return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt");
                        })
                        .sorted(Comparator.naturalOrder())
                        .map(path -> new Entry(stem(path.getFileName().toString()),
                                () -> Files.readString(path, StandardCharsets.UTF_8)))
                        .toList();
            }
        }
    }

    /** Classpath entries enumerated by {@code base/index.txt}; each line is a resource file name. */
    private static final class ClasspathSource implements Source {
        private final String base;
        private final ClassLoader loader;

        ClasspathSource(String base, ClassLoader loader) {
            this.base = Objects.requireNonNull(base, "base");
            this.loader = Objects.requireNonNull(loader, "loader");
        }

        @Override
        public String describe() {
            return "classpath:" + base;
        }

        @Override
        public List<Entry> entries() throws IOException {
            try (InputStream index = loader.getResourceAsStream(base + "/index.txt")) {
                if (index == null) {
                    return List.of();
                }
                String text = new String(index.readAllBytes(), StandardCharsets.UTF_8);
                return text.lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .sorted()
                        .map(line -> {
                            String resource = base + "/" + line;
                            String name = stem(line);
                            return new Entry(name, () -> {
                                try (InputStream input = loader.getResourceAsStream(resource)) {
                                    if (input == null) {
                                        throw new IOException("missing classpath resource: " + resource);
                                    }
                                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                                }
                            });
                        })
                        .toList();
            }
        }
    }

    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}