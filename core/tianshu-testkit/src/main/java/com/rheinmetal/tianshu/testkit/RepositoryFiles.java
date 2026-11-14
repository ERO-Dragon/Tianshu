package com.rheinmetal.tianshu.testkit;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Walks a repository tree once, for tests that inspect sources or language shards.
 *
 * <p>The working tree also holds model distributions under {@code libs/}, build output, run directories and
 * version-control data. None of them can contain a language shard or a shipped source file, and descending into
 * them is both wasted work and a hazard: a dangling symbolic link would fail the walk even though the file the
 * test looks for is elsewhere. Those directories are pruned and symbolic links are not followed, so the walk
 * only produces regular files from the parts of the tree that matter.</p>
 */
public final class RepositoryFiles {

    private static final Set<String> IGNORED_DIRECTORIES =
            Set.of("libs", "build", "bin", "run", "runs", ".git", ".gradle", "node_modules");

    private RepositoryFiles() {
    }

    /** Every regular file under {@code root}, pruned as described above and sorted for deterministic tests. */
    public static List<Path> regularFiles(Path root) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(root)
                            && IGNORED_DIRECTORIES.contains(directory.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            throw new IllegalStateException("cannot walk " + root, failure);
        }
        files.sort(Comparator.naturalOrder());
        return files;
    }
}