package com.rheinmetal.tianshu.function.echo.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rheinmetal.tianshu.model.ModelDownloadFileMover;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Per-model playback settings of a TTS voice.
 *
 * <p>Speed, speaker and the selected voice sample are the synthesizer's parameters, so they live with the
 * module that owns the synthesizer. The file name stays {@code model-settings.json} inside the model
 * directory, which is what the previous host-owned container used — a model directory written by an older
 * build keeps working.</p>
 */
public final class TtsModelSettings {

    private static final String SETTINGS_FILE = "model-settings.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public double speed = 1.0;
    public int speakerId = 0;
    public String selectedVoiceSample = "";

    public static TtsModelSettings load(Path modelDir) {
        if (modelDir == null) {
            return new TtsModelSettings();
        }
        Path file = modelDir.resolve(SETTINGS_FILE);
        if (!Files.exists(file)) {
            return new TtsModelSettings();
        }
        try {
            String json = Files.readString(file);
            TtsModelSettings result = GSON.fromJson(json, TtsModelSettings.class);
            return result != null ? result : new TtsModelSettings();
        } catch (Exception malformed) {
            return new TtsModelSettings();
        }
    }

    public static void save(Path modelDir, TtsModelSettings settings) {
        TtsModelSettings effective = settings == null ? new TtsModelSettings() : settings;
        Path staging = null;
        try {
            Files.createDirectories(modelDir);
            Path file = modelDir.resolve(SETTINGS_FILE);
            staging = Files.createTempFile(modelDir, ".model-settings-", ".tmp");
            Files.writeString(staging, GSON.toJson(effective), StandardCharsets.UTF_8);
            ModelDownloadFileMover.moveReplacing(staging, file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } finally {
            if (staging != null) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException cleanupFailure) {
                    // The save result must not be replaced by a temporary-file cleanup error.
                    staging.toFile().deleteOnExit();
                }
            }
        }
    }
}
