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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * File-backed {@code resource} and {@code prompt} capabilities. Each root directory holds flat text files; the file
 * stem is the name, and a standard front-matter block supplies {@code name} and {@code description}.
 *
 * <p>Resources are read with optional paging. Prompts are templates: {@code {{key}}} placeholders are replaced from
 * the call's {@code arguments} object; a key that is absent is left visible rather than silently blanked, so the
 * caller can see what it forgot.</p>
 */
public final class TextCapabilityProvider {

    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final int MAX_NAME = 64;
    private static final int MAX_DESCRIPTION = 1024;
    private static final int MAX_TEXT_BYTES = 512 * 1024;

    private final CapabilityKind kind;
    private final String namespace;
    private final Path root;
    private final Map<CapabilityId, Loaded> loaded = new ConcurrentHashMap<>();
    private final List<String> problems = new java.util.ArrayList<>();

    private record Loaded(String name, String description, Path file) {
    }

    private TextCapabilityProvider(CapabilityKind kind, String namespace, Path root) {
        if (!kind.equals(CapabilityKind.RESOURCE) && !kind.equals(CapabilityKind.PROMPT)) {
            throw new IllegalArgumentException("text provider supports resource/prompt, not " + kind);
        }
        this.kind = kind;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public static TextCapabilityProvider install(CapabilityKind kind, String namespace, Path root) {
        TextCapabilityProvider provider = new TextCapabilityProvider(kind, namespace, root);
        provider.reload();
        return provider;
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    public synchronized void reload() {
        unload();
        problems.clear();
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> paths = Files.list(root)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt");
                    })
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            problems.add("cannot list " + root + ": " + failure.getMessage());
            return;
        }
        for (Path file : files) {
            load(file);
        }
    }

    public synchronized void unload() {
        for (CapabilityId id : loaded.keySet()) {
            CapabilityCatalog.unregister(id);
        }
        loaded.clear();
    }

    private void load(Path file) {
        String stem = stem(file);
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            FrontMatter front = FrontMatter.parse(text);
            String name = front.get("name");
            String description = front.get("description");
            if (name == null || !NAME.matcher(name).matches() || name.length() > MAX_NAME) {
                problems.add(file + ": name must be lowercase words joined by hyphens");
                return;
            }
            if (!name.equals(stem)) {
                problems.add(file + ": name \"" + name + "\" does not match the file name \"" + stem + "\"");
                return;
            }
            if (description == null || description.isBlank()) {
                problems.add(file + ": front matter has no description");
                return;
            }
            String summary = description.strip();
            if (summary.length() > MAX_DESCRIPTION) {
                summary = summary.substring(0, MAX_DESCRIPTION);
            }
            CapabilityId id = CapabilityId.of(namespace, name);
            Loaded loadedCapability = new Loaded(name, summary, file);
            JsonObject attributes = new JsonObject();
            attributes.addProperty("name", name);
            attributes.addProperty("source", kind.value());
            attributes.addProperty("mediaType", "text/markdown");
            CapabilityDescriptor descriptor = new CapabilityDescriptor(id, kind, CapabilityBinding.LOCAL, summary,
                    ToolSide.CLIENT, ToolPermission.EVERYONE, "tianshu-text", attributes);
            CapabilityCatalog.register(descriptor, (context, args) -> read(loadedCapability, args));
            loaded.put(id, loadedCapability);
        } catch (IOException | RuntimeException failure) {
            problems.add(file + ": " + failure.getMessage());
        }
    }

    private CompletableFuture<JsonObject> read(Loaded capability, JsonObject args) {
        try {
            String text = Files.readString(capability.file(), StandardCharsets.UTF_8);
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

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
