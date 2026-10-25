package com.rheinmetal.tianshu.client.settings;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientSettingsDeviceBoundaryTest {
    @Test
    void asrSettingsReadsCachedMicrophonesAndRequestsAsyncRefresh() throws Exception {
        String source = Files.readString(
                SettingsSourcePaths.page("intake", "IntakeSettingsRegistrySource.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("audioDeviceCatalog.currentMicNames()"));
        assertTrue(source.contains("refreshMicrophoneNames()"));
        assertTrue(source.contains("microphoneRefreshStarted.compareAndSet(false, true)"));
        assertFalse(source.contains("audioBridge.getAvailableMicNames()"));
    }

    @Test
    void modelPagesReadAvailabilitySnapshotsInsteadOfScanningModelFiles() throws Exception {
        assertSnapshotBoundary("intake/IntakeSettingsRegistrySource.java");
        assertSnapshotBoundary("weaving/WeavingSettingsRegistrySource.java");
        assertSnapshotBoundary("echo/EchoSettingsRegistrySource.java");
    }

    private static void assertSnapshotBoundary(String relativePath) throws Exception {
        String source = Files.readString(
                SettingsSourcePaths.page(relativePath),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("modelAvailability()"), relativePath);
        assertTrue(source.contains("refreshModelAvailabilityAsync"), relativePath);
        assertFalse(source.contains("hasModelContent("), relativePath);
        assertFalse(source.contains("Files.walk("), relativePath);
    }

    @Test
    void ttsVoiceLibraryWorkUsesTheModuleIoBoundary() throws Exception {
        String source = Files.readString(
                SettingsSourcePaths.page("echo", "EchoSettingsRegistrySource.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("voiceSamples()"));
        assertTrue(source.contains("refreshVoiceSamplesAsync"));
        assertTrue(source.contains("importVoiceSampleAsync"));
        assertFalse(source.contains("listVoiceSamples()"));
        assertFalse(source.contains("importVoiceSample(selected)"));
    }
}
