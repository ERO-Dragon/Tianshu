package com.rheinmetal.tianshu.function.echo.model;

import java.nio.file.Path;

/**
 * The one place that decides where a TTS model lives on disk.
 *
 * <p>The model directory name is not always the catalog name ({@code zipvoice} ships under
 * {@code ZipVoice}), so the settings snapshot, the model service and the completeness check used to
 * repeat both the root and that alias. Keeping the rule here is what lets the alias exist once.</p>
 */
public final class TtsModelLayout {

    public static Path modelRoot(Path ttsBasePath) {
        return ttsBasePath.resolve("model").normalize();
    }

    public static Path modelDirectory(Path ttsBasePath, TtsModelInfo info) {
        return modelDirectory(ttsBasePath, info.modelDirectoryName());
    }

    /**
     * Resolves a directory name (or a configured name that may contain subdirectories) under the model
     * root, refusing anything that would escape it.
     */
    public static Path modelDirectory(Path ttsBasePath, String directoryName) {
        Path root = modelRoot(ttsBasePath);
        Path directory = root.resolve(directoryName).normalize();
        if (!directory.startsWith(root)) {
            throw new IllegalArgumentException("TTS model directory escapes the model root: " + directoryName);
        }
        return directory;
    }

    private TtsModelLayout() {
    }
}
