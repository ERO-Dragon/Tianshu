package com.rheinmetal.tianshu.function.echo.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import static org.junit.jupiter.api.Assertions.*;

class TtsModelSettingsTest {
    @TempDir Path directory;

    @Test
    void unwritableModelSettingsAreReportedToCaller() throws Exception {
        Path model = directory.resolve("not-a-directory");
        Files.writeString(model, "keep");
        assertThrows(UncheckedIOException.class, () -> TtsModelSettings.save(model, new TtsModelSettings()));
        assertEquals("keep", Files.readString(model));
    }

    @Test
    void replacingSettingsPreservesValuesAndLeavesNoStagingFile() throws Exception {
        TtsModelSettings.save(directory, new TtsModelSettings());
        var settings = new TtsModelSettings();
        settings.speed = 1.5;
        settings.speakerId = 2;
        settings.selectedVoiceSample = "voice.wav";
        TtsModelSettings.save(directory, settings);
        var loaded = TtsModelSettings.load(directory);
        assertEquals(1.5, loaded.speed);
        assertEquals(2, loaded.speakerId);
        assertEquals("voice.wav", loaded.selectedVoiceSample);
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
}
