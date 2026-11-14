package com.rheinmetal.tianshu.function.echo.settings;

import com.rheinmetal.tianshu.function.echo.synthesis.cloud.TtsCloudSettings;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which settings changes require the runtime to be rebuilt.
 *
 * <p>Switching between local and cloud, changing the provider, the endpoint or the voice all change what the
 * backend has to be, so every one of them takes the restart path; changing only the preview text must not.</p>
 */
class TtsSettingsChangeSetTest {

    private static final TtsCloudSettings LOCAL = new TtsCloudSettings("", "", "");

    @Test
    void switchingBetweenLocalAndCloudRestartsTheRuntime() {
        TtsSettingsChangeSet change = TtsSettingsChangeSet.between(
                snapshot("local", LOCAL), snapshot("cloud", LOCAL));

        assertTrue(change.backendChanged());
        assertTrue(change.requiresRuntimeRestart());
    }

    @Test
    void changingTheCloudProviderOrVoiceRestartsTheRuntime() {
        TtsSettingsChangeSet provider = TtsSettingsChangeSet.between(
                snapshot("cloud", new TtsCloudSettings("edge", "", "")),
                snapshot("cloud", new TtsCloudSettings("edge", "", "zh-CN-YunxiNeural")));

        assertTrue(provider.backendChanged());
        assertTrue(provider.requiresRuntimeRestart());

        TtsSettingsChangeSet endpoint = TtsSettingsChangeSet.between(
                snapshot("cloud", new TtsCloudSettings("edge", "", "")),
                snapshot("cloud", new TtsCloudSettings("edge", "wss://proxy.invalid/v1", "")));

        assertTrue(endpoint.backendChanged());
    }

    @Test
    void anUnrelatedChangeDoesNotRestartTheRuntime() {
        TtsSettingsChangeSet change = TtsSettingsChangeSet.between(
                snapshot("local", LOCAL), snapshot("local", LOCAL));

        assertFalse(change.requiresRuntimeRestart());
        assertFalse(change.backendChanged());
    }

    @Test
    void disablingTheModuleTakesTheStopPathInsteadOfARestart() {
        TtsSettingsSnapshot enabled = new TtsSettingsSnapshot(true, "", null, "local", LOCAL);
        TtsSettingsSnapshot disabled = new TtsSettingsSnapshot(false, "", null, "local", LOCAL);

        TtsSettingsChangeSet change = TtsSettingsChangeSet.between(enabled, disabled);

        assertTrue(change.disabled());
        assertTrue(change.requiresRuntimeRestart());
    }

    @Test
    void aMissingSnapshotIsTreatedAsNoChange() {
        TtsSettingsChangeSet change = TtsSettingsChangeSet.between(null, snapshot("local", LOCAL));

        assertFalse(change.requiresRuntimeRestart());
    }

    private static TtsSettingsSnapshot snapshot(String mode, TtsCloudSettings cloud) {
        return new TtsSettingsSnapshot(true, "", Path.of("model"), mode, cloud);
    }
}