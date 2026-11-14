package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import com.rheinmetal.tianshu.function.echo.settings.EchoConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cloud settings are mostly about defaults: an empty override must fall back to the catalog, and a player who
 * only picks a voice must not also have to repeat the endpoint.
 */
class TtsCloudSettingsTest {

    @Test
    void anEmptySelectionFallsBackToTheCatalogDefaults() {
        TtsCloudSettings settings = TtsCloudSettings.from(config(Map.of()));

        TtsCloudProfile profile = settings.toProfile();
        TtsCloudProviders.Provider provider = TtsCloudProviders.defaultProvider();

        assertEquals(provider.id(), profile.providerId());
        assertTrue(profile.endpoint().isEmpty(), "the endpoint override stays empty; the transport uses the catalog");
        assertTrue(profile.voiceId().isEmpty(), "the voice override stays empty; the transport uses the catalog");
    }

    @Test
    void overridesAreKeptVerbatim() {
        TtsCloudSettings settings = TtsCloudSettings.from(config(Map.of(
                "cloudProvider", "edge",
                "cloudEndpoint", " wss://proxy.invalid/v1 ",
                "cloudVoice", "zh-CN-YunxiNeural")));

        TtsCloudProfile profile = settings.toProfile();

        assertEquals("edge", profile.providerId());
        assertEquals("wss://proxy.invalid/v1", profile.endpoint());
        assertEquals("zh-CN-YunxiNeural", profile.voiceId());
        assertTrue(profile.hasEndpointOverride());
        assertTrue(profile.hasVoiceOverride());
    }

    @Test
    void modeIsReadCaseInsensitivelyAndDefaultsToLocal() {
        assertFalse(TtsCloudSettings.isCloudMode(config(Map.of())));
        assertFalse(TtsCloudSettings.isCloudMode(config(Map.of("backendMode", "local"))));
        assertTrue(TtsCloudSettings.isCloudMode(config(Map.of("backendMode", "cloud"))));
        assertTrue(TtsCloudSettings.isCloudMode(config(Map.of("backendMode", " CLOUD "))));
    }

    @Test
    void anUnknownProviderStillProducesAProfileThatFailsAtConnectTime() {
        TtsCloudSettings settings = TtsCloudSettings.from(config(Map.of("cloudProvider", "no-such-provider")));

        TtsCloudProfile profile = settings.toProfile();

        assertEquals("no-such-provider", profile.providerId(),
                "an unknown id is kept so the failure names what the player configured");
        assertTrue(settings.provider().isEmpty());
    }

    private static EchoConfiguration config(Map<String, String> values) {
        Map<String, String> copy = new HashMap<>(values);
        return new EchoConfiguration() {
            @Override
            public boolean isTtsEnabled() {
                return true;
            }

            @Override
            public String getCustomTtsName() {
                return "";
            }

            @Override
            public java.nio.file.Path getTtsBasePath() {
                return java.nio.file.Path.of(".");
            }

            @Override
            public java.nio.file.Path getVoiceLibraryPath() {
                return java.nio.file.Path.of(".");
            }

            @Override
            public String getTtsBackendMode() {
                return copy.getOrDefault("backendMode", "local");
            }

            @Override
            public String getCloudProviderId() {
                return copy.getOrDefault("cloudProvider", "");
            }

            @Override
            public String getCloudEndpoint() {
                return copy.getOrDefault("cloudEndpoint", "");
            }

            @Override
            public String getCloudVoice() {
                return copy.getOrDefault("cloudVoice", "");
            }
        };
    }
}