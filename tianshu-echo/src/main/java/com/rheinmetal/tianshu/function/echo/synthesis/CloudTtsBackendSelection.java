package com.rheinmetal.tianshu.function.echo.synthesis;

import com.rheinmetal.tianshu.function.echo.synthesis.cloud.TtsCloudProfile;

import java.util.Objects;

/**
 * A selection served by a cloud provider instead of a model directory.
 *
 * <p>It lives beside the local variant because a sealed type's permitted subclasses must share its package
 * in the unnamed module; keeping the two together is also what makes the provider's dispatch exhaustive
 * without a default branch.</p>
 *
 * <p>Equality is the profile's: the provider, the endpoint override and the voice. That is what the engine
 * provider compares to decide whether switching is a no-op — two requests for the same voice on the same
 * endpoint must not tear down and rebuild the connection.</p>
 */
public record CloudTtsBackendSelection(TtsCloudProfile profile) implements TtsBackendSelection {

    public CloudTtsBackendSelection {
        Objects.requireNonNull(profile, "profile");
    }

    @Override
    public TtsBackendType backendType() {
        return TtsBackendType.CLOUD;
    }

    /** Provider and voice as facts, not as a translated phrase; the settings page formats them. */
    @Override
    public String label() {
        String voice = profile.voiceId().isEmpty() ? "" : " / " + profile.voiceId();
        return profile.providerId() + voice;
    }
}