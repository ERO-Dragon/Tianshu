package com.rheinmetal.tianshu.api.assembly;

import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the resources an installed mod ships inside its own file.
 *
 * <p>A module is platform-free, so it cannot open a jar or ask the loader for a path. This port is the only
 * way it reaches a partner's content. It is deliberately small: two operations, both expressed in the
 * resource paths every loader already uses ({@code assets/<namespace>/...}).</p>
 *
 * <p>Paths are always relative to the mod's root and use {@code /} separators. A missing directory or file is
 * an empty answer, never an exception: most mods simply do not ship the content a caller is looking for, and a
 * caller must be able to walk every mod without special-casing the ones that opt out.</p>
 *
 * <p>Implementations are provided by the platform adapter. When nothing is registered — the unit-test or
 * headless case — {@link #none()} answers empty for everything.</p>
 */
public interface ModResources {

    /**
     * The direct children of {@code directory}, as bare names, in a stable order.
     *
     * <p>An empty list means "no such directory" and "empty directory" alike; callers that need to tell them
     * apart should {@link #read(String, String)} a known member instead.</p>
     */
    List<String> children(String modId, String directory);

    /** The UTF-8 content of {@code path}, or empty when the mod does not ship it. */
    Optional<String> read(String modId, String path);

    /** The stand-in used when nothing is registered: every mod looks empty. */
    static ModResources none() {
        return new ModResources() {
            @Override
            public List<String> children(String modId, String directory) {
                return List.of();
            }

            @Override
            public Optional<String> read(String modId, String path) {
                return Optional.empty();
            }
        };
    }
}