package com.rheinmetal.tianshu.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    /**
     * The directory that owns the product documentation. Documentation is maintained once, in the entry
     * worktree, so a linked worktree resolves it across the shared {@code .git} instead of keeping a copy.
     */
    public static Path docRoot() {
        return docRoot(workspaceRoot(), System.getProperty("tianshu.docRoot"));
    }

    /** Resolves the documentation root for an explicit worktree root; {@code configured} overrides everything. */
    public static Path docRoot(Path worktreeRoot, String configured) {
        if (configured != null && !configured.isBlank()) {
            Path configuredRoot = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(configuredRoot)) {
                return configuredRoot;
            }
        }
        Path local = worktreeRoot.resolve("doc");
        if (Files.isDirectory(local)) {
            return local;
        }
        return mainWorktreeRoot(worktreeRoot)
                .map(root -> root.resolve("doc"))
                .filter(Files::isDirectory)
                .orElseThrow(() -> new IllegalStateException(
                        "no doc/ in " + worktreeRoot + " or its main worktree; set -Dtianshu.docRoot"));
    }

    /** One documentation file, resolved relative to {@link #docRoot()}. */
    public static Path doc(String relative) {
        Path root = docRoot();
        Path file = root.resolve(relative).normalize();
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("cannot find doc/" + relative + " under " + root);
        }
        return file;
    }

    /**
     * The main worktree owning this checkout's git directory, when this checkout is a linked worktree.
     * A main worktree has a {@code .git} directory; a linked one has a {@code .git} file pointing at
     * {@code <main>/.git/worktrees/<name>}.
     */
    static Optional<Path> mainWorktreeRoot(Path worktreeRoot) {
        Path dotGit = worktreeRoot.resolve(".git");
        if (!Files.isRegularFile(dotGit)) {
            return Optional.empty();
        }
        String pointer;
        try {
            pointer = Files.readString(dotGit, StandardCharsets.UTF_8).trim();
        } catch (IOException failure) {
            return Optional.empty();
        }
        String prefix = "gitdir:";
        if (!pointer.startsWith(prefix)) {
            return Optional.empty();
        }
        Path gitDir = Path.of(pointer.substring(prefix.length()).trim()).toAbsolutePath().normalize();
        Path worktrees = gitDir.getParent();
        if (worktrees == null || !hasFileName(worktrees, "worktrees")) {
            return Optional.empty();
        }
        Path owner = worktrees.getParent();
        if (owner == null || !hasFileName(owner, ".git")) {
            return Optional.empty();
        }
        return Optional.ofNullable(owner.getParent());
    }

    private static boolean hasFileName(Path path, String name) {
        return path.getFileName() != null && path.getFileName().toString().equals(name);
    }

    private static void addIfSet(LinkedHashSet<Path> roots, String property) {
        String configured = System.getProperty(property);
        if (configured != null && !configured.isBlank()) {
            roots.add(Path.of(configured));
        }
    }
}