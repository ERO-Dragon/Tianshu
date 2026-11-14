package com.rheinmetal.tianshu.testkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** Resolves source paths in the split workspace (adapter + ferry + features). */
public final class WorkspacePaths {

    private WorkspacePaths() {
    }

    public static Path workspaceRoot() {
        return LangShards.repositoryRoot();
    }

    public static List<Path> roots() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(workspaceRoot());
        addIfSet(roots, "tianshu.ferryRoot");
        addIfSet(roots, "tianshu.featuresRoot");
        return List.copyOf(roots);
    }

    public static Optional<Path> find(String relative) {
        for (Path root : roots()) {
            Path candidate = root.resolve(relative).normalize();
            if (Files.exists(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public static Path require(String relative) {
        return find(relative).orElseThrow(() -> new IllegalStateException(
                "cannot find " + relative + " under " + roots()));
    }

    private static void addIfSet(LinkedHashSet<Path> roots, String property) {
        String configured = System.getProperty(property);
        if (configured != null && !configured.isBlank()) {
            roots.add(Path.of(configured));
        }
    }
}
