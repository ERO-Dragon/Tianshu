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

    @Test
    void unconfiguredDirectorySpeaksWithTheCatalogVoice() {
        var loaded = TtsModelSettings.load(directory, catalogVoice(7));
        assertEquals(7, loaded.speakerId);
    }

    @Test
    void savedSettingsOutrankTheCatalogVoice() {
        var settings = new TtsModelSettings();
        settings.speakerId = 0;
        TtsModelSettings.save(directory, settings);
        assertEquals(0, TtsModelSettings.load(directory, catalogVoice(7)).speakerId);
    }

    @Test
    void malformedSettingsFallBackToTheCatalogVoice() throws Exception {
        Files.writeString(directory.resolve("model-settings.json"), "{ not json");
        assertEquals(7, TtsModelSettings.load(directory, catalogVoice(7)).speakerId);
    }

    @Test
    void entriesWithoutADeclaredVoiceKeepSpeakingWithIndexZero() {
        assertEquals(0, TtsModelSettings.load(directory, new TtsModelInfo()).speakerId);
    }

    private static TtsModelInfo catalogVoice(int speakerId) {
        var info = new TtsModelInfo();
        info.voicesFile = "voices.bin";
        info.defaultSpeakerId = speakerId;
        return info;
    }
}
