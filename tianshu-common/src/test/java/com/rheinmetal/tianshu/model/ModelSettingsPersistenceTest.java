package com.rheinmetal.tianshu.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import static org.junit.jupiter.api.Assertions.*;

class ModelSettingsPersistenceTest {
    @TempDir Path directory;

    @Test
    void unwritableModelSettingsAreReportedToCaller() throws Exception {
        Path model = directory.resolve("not-a-directory");
        Files.writeString(model, "keep");
        assertThrows(UncheckedIOException.class, () -> ModelSettings.saveTtsSettings(model, new ModelSettings.TtsSettings()));
        assertEquals("keep", Files.readString(model));
    }

    @Test
    void replacingSettingsPreservesValuesAndLeavesNoStagingFile() throws Exception {
        ModelSettings.saveTtsSettings(directory, new ModelSettings.TtsSettings());
        var settings = new ModelSettings.TtsSettings();
        settings.speed = 1.5;
        settings.speakerId = 2;
        settings.selectedVoiceSample = "voice.wav";
        ModelSettings.saveTtsSettings(directory, settings);
        var loaded = ModelSettings.loadTtsSettings(directory);
        assertEquals(1.5, loaded.speed);
        assertEquals(2, loaded.speakerId);
        assertEquals("voice.wav", loaded.selectedVoiceSample);
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
}
