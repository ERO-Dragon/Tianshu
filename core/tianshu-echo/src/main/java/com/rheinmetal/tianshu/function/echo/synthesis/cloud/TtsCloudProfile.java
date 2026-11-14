package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

/**
 * Where a cloud backend speaks, and in whose voice.
 *
 * <p>It carries no model: that is the whole point of the cloud selection. The provider id names the catalog
 * entry (endpoint defaults, voice list), while {@code endpoint} and {@code voiceId} are the player's
 * overrides — empty means "use the provider default", which is why an override never has to be repeated when
 * the catalog default changes.</p>
 */
public record TtsCloudProfile(String providerId, String endpoint, String voiceId, long timeoutMillis) {

    private static final long DEFAULT_TIMEOUT_MILLIS = 15_000L;

    public TtsCloudProfile {
        providerId = providerId == null ? "" : providerId.trim();
        endpoint = endpoint == null ? "" : endpoint.trim();
        voiceId = voiceId == null ? "" : voiceId.trim();
        timeoutMillis = timeoutMillis <= 0L ? DEFAULT_TIMEOUT_MILLIS : timeoutMillis;
    }

    public boolean hasEndpointOverride() {
        return !endpoint.isEmpty();
    }

    public boolean hasVoiceOverride() {
        return !voiceId.isEmpty();
    }
}