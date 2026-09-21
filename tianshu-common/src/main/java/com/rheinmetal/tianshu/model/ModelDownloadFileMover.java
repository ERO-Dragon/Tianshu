package com.rheinmetal.tianshu.model;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Promotes a completed download while tolerating file-system limits on atomic replacement. */
final class ModelDownloadFileMover {
    private ModelDownloadFileMover() {
    }

    static void moveReplacing(Path source, Path target) throws IOException {
        moveReplacing(source, target, Files::move);
    }

    static void moveReplacing(Path source, Path target, MoveOperation operation) throws IOException {
        try {
            operation.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileSystemException unsupported) {
            operation.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    interface MoveOperation {
        Path move(Path source, Path target, java.nio.file.CopyOption... options) throws IOException;
    }
}
