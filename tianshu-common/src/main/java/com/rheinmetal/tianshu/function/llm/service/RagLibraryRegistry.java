package com.rheinmetal.tianshu.function.llm.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RagLibraryRegistry {
    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String VISIBILITY_SHARED = "SHARED";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type META_LIST_TYPE = new TypeToken<List<RagLibraryMeta>>() {}.getType();

    private final IGameEnvironment env;
    private final Path registryFile;
    private final Map<String, RagLibraryMeta> libraries = new ConcurrentHashMap<>();

    public RagLibraryRegistry(IGameEnvironment env, Path cacheDirectory) {
        this.env = Objects.requireNonNull(env, "env");
        this.registryFile = cacheDirectory == null ? null : cacheDirectory.resolve("libraries.json");
        load();
    }

    public void register(String uid, String modid, String visibility, List<String> tags) {
        String cleanUid = clean(uid);
        if (cleanUid.isBlank()) {
            return;
        }
        libraries.put(cleanUid, new RagLibraryMeta(cleanUid, cleanModid(modid), normalizeVisibility(visibility), new ArrayList<>(normalizeTags(tags))));
        save();
    }

    public void unregister(String uid) {
        String cleanUid = clean(uid);
        if (!cleanUid.isBlank() && libraries.remove(cleanUid) != null) {
            save();
        }
    }

    public RagLibraryMeta meta(String uid) {
        return libraries.get(clean(uid));
    }

    public List<RagLibraryMeta> sharedByModid(String modid) {
        String cleanModid = cleanModid(modid);
        if (cleanModid.isBlank()) {
            return List.of();
        }
        return libraries.values().stream()
                .filter(RagLibraryMeta::shared)
                .filter(meta -> meta.modid().equals(cleanModid))
                .sorted()
                .toList();
    }

    public List<RagLibraryMeta> sharedByTags(List<String> tags) {
        Set<String> requested = normalizeTags(tags);
        if (requested.isEmpty()) {
            return List.of();
        }
        return libraries.values().stream()
                .filter(RagLibraryMeta::shared)
                .filter(meta -> meta.hasAnyTag(requested))
                .sorted()
                .toList();
    }

    public List<RagLibraryMeta> sharedLibraries() {
        return libraries.values().stream()
                .filter(RagLibraryMeta::shared)
                .sorted()
                .toList();
    }

    private void load() {
        if (registryFile == null || !Files.isRegularFile(registryFile)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(registryFile, StandardCharsets.UTF_8)) {
            List<RagLibraryMeta> loaded = GSON.fromJson(reader, META_LIST_TYPE);
            if (loaded == null) {
                return;
            }
            for (RagLibraryMeta meta : loaded) {
                if (meta != null && !clean(meta.uid()).isBlank()) {
                    libraries.put(clean(meta.uid()), meta.normalized());
                }
            }
        } catch (Exception e) {
            env.error("[RAG] Failed to load RAG library registry", e);
        }
    }

    private void save() {
        if (registryFile == null) {
            return;
        }
        try {
            Files.createDirectories(registryFile.getParent());
            List<RagLibraryMeta> snapshot = libraries.values().stream().sorted().toList();
            try (Writer writer = Files.newBufferedWriter(registryFile, StandardCharsets.UTF_8)) {
                GSON.toJson(snapshot, META_LIST_TYPE, writer);
            }
        } catch (Exception e) {
            env.error("[RAG] Failed to save RAG library registry", e);
        }
    }

    private static String normalizeVisibility(String value) {
        String normalized = clean(value).toUpperCase(Locale.ROOT);
        return VISIBILITY_PRIVATE.equals(normalized) ? VISIBILITY_PRIVATE : VISIBILITY_SHARED;
    }

    private static String cleanModid(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String tag : tags) {
            String cleanTag = clean(tag).toLowerCase(Locale.ROOT);
            if (!cleanTag.isBlank()) {
                result.add(cleanTag);
            }
        }
        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public record RagLibraryMeta(String uid, String modid, String visibility, List<String> tags) implements Comparable<RagLibraryMeta> {
        public RagLibraryMeta {
            uid = clean(uid);
            modid = cleanModid(modid);
            visibility = normalizeVisibility(visibility);
            tags = List.copyOf(new ArrayList<>(normalizeTags(tags)));
        }

        RagLibraryMeta normalized() {
            return new RagLibraryMeta(uid, modid, visibility, tags);
        }

        boolean shared() {
            return VISIBILITY_SHARED.equals(visibility);
        }

        boolean hasAnyTag(Set<String> requested) {
            if (requested == null || requested.isEmpty() || tags.isEmpty()) {
                return false;
            }
            for (String tag : tags) {
                if (requested.contains(tag)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int compareTo(RagLibraryMeta other) {
            if (other == null) {
                return 1;
            }
            return uid.compareTo(other.uid);
        }
    }
}
