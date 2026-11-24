package com.rheinmetal.tianshu.mc.resource;

import com.rheinmetal.tianshu.api.assembly.ModResources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * {@link ModResources} over the loader's own view of a mod's files, shared by every adapter.
 *
 * <p>Both loaders already mount every mod's file into a read-only file system and can resolve a resource path
 * inside it; what differs is the call that resolves it. So the adapters keep only that one call — the walking
 * and reading below is identical, and lives here rather than being copied into each adapter.</p>
 *
 * <p>A resolver returns {@code null} when the mod or the path does not exist, and is allowed to throw: a
 * loader is free to reject a malformed path, and one bad path must not abort a walk over every mod.</p>
 */
public final class ModResourceFiles {

    private ModResourceFiles() {
    }

    /** @param resolve {@code (modId, path) -> mounted path}, or {@code null} when there is nothing there */
    public static ModResources over(BiFunction<String, String, Path> resolve) {
        return new ModResources() {
            @Override
            public List<String> children(String modId, String directory) {
                Path path = lookup(modId, directory);
                if (path == null || !Files.isDirectory(path)) {
                    return List.of();
                }
                try (Stream<Path> entries = Files.list(path)) {
                    return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
                } catch (IOException | RuntimeException failure) {
                    return List.of();
                }
            }

            @Override
            public Optional<String> read(String modId, String path) {
                Path resolved = lookup(modId, path);
                if (resolved == null || !Files.isRegularFile(resolved)) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(Files.readString(resolved, StandardCharsets.UTF_8));
                } catch (IOException | RuntimeException failure) {
                    return Optional.empty();
                }
            }

            private Path lookup(String modId, String path) {
                if (modId == null || modId.isBlank() || path == null || path.isEmpty()) {
                    return null;
                }
                try {
                    return resolve.apply(modId, path);
                } catch (RuntimeException failure) {
                    return null;
                }
            }
        };
    }
}