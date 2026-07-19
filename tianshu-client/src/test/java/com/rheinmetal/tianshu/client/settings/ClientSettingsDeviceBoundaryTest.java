package com.rheinmetal.tianshu.client.settings;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientSettingsDeviceBoundaryTest {
    @Test
    void asrSettingsReadsCachedMicrophonesAndRequestsAsyncRefresh() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/client/settings/module/asr/AsrSettingsRegistrySource.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("audioDeviceCatalog.currentMicNames()"));
        assertTrue(source.contains("refreshMicrophoneNames()"));
        assertTrue(source.contains("microphoneRefreshStarted.compareAndSet(false, true)"));
        assertFalse(source.contains("audioBridge.getAvailableMicNames()"));
    }
}
