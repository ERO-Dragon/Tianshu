package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import com.rheinmetal.tianshu.function.echo.settings.EchoConfiguration;

/**
 * The player's cloud choice, resolved against the provider catalog.
 *
 * <p>Empty overrides are allowed everywhere because the catalog is the source of defaults: a player who only
 * picks a voice does not also have to repeat the endpoint, and a catalog endpoint change then reaches them
 * without a migration. Resolution lives here rather than in the engine so the "what will actually be used"
 * question has one answer that a test can ask directly.</p>
 */
public record TtsCloudSettings(String providerId, String endpoint, String voiceId) {

    public static final String MODE_LOCAL = "local";
    public static final String MODE_CLOUD = "cloud";

    public TtsCloudSettings {
        providerId = providerId == null ? "" : providerId.trim();
        endpoint = endpoint == null ? "" : endpoint.trim();
        voiceId = voiceId == null ? "" : voiceId.trim();
    }

    public static TtsCloudSettings from(EchoConfiguration config) {
        if (config == null) {
            return new TtsCloudSettings("", "", "");
        }
        return new TtsCloudSettings(
                config.getCloudProviderId(),
                config.getCloudEndpoint(),
                config.getCloudVoice());
    }

    /** @return whether the player selected the cloud backend. */
    public static boolean isCloudMode(EchoConfiguration config) {
        return config != null && MODE_CLOUD.equalsIgnoreCase(trim(config.getTtsBackendMode()));
    }

    /** The catalog entry this selection names, or empty when it names an unknown provider. */
    public java.util.Optional<TtsCloudProviders.Provider> provider() {
        if (!providerId.isEmpty()) {
            return TtsCloudProviders.byId(providerId);
        }
        return java.util.Optional.of(TtsCloudProviders.defaultProvider());
    }

    /** The profile the request will use, with catalog defaults filled in. */
    public TtsCloudProfile toProfile() {
        java.util.Optional<TtsCloudProviders.Provider> provider = provider();
        String resolvedProvider = provider.map(TtsCloudProviders.Provider::id)
                .orElse(providerId.isEmpty() ? TtsCloudProviders.defaultProvider().id() : providerId);
        return new TtsCloudProfile(resolvedProvider, endpoint, voiceId, 0L);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}