package com.rheinmetal.tianshu.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/** Locates a version branch's adapter/common source without naming a Minecraft version. */
public final class AdapterSources {

    private AdapterSources() {
    }

    public static Path adaptersRoot() {
        return LangShards.repositoryRoot().resolve("tianshu-adapter");
    }

    public static Path adapter(String suffix, String relativeFile) {
        return find(adaptersRoot(), suffix, relativeFile)
                .orElseThrow(() -> new IllegalStateException(
                        "no *" + suffix + " adapter with " + relativeFile + " under " + adaptersRoot()));
    }

    public static Path common(String relativeFile) {
        return find(adaptersRoot(), "-common", relativeFile)
                .orElseThrow(() -> new IllegalStateException(
                        "no *-common adapter with " + relativeFile + " under " + adaptersRoot()));
    }

    private static Optional<Path> find(Path root, String suffix, String relativeFile) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .filter(path -> !relativeFile.isBlank() && Files.isRegularFile(path.resolve(relativeFile)))
                    .findFirst();
        } catch (IOException failure) {
            return Optional.empty();
        }
    }
}
