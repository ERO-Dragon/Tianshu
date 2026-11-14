package com.rheinmetal.tianshu.function.echo.settings;

import java.nio.file.Path;

public interface EchoConfiguration {
    boolean isTtsEnabled();

    String getCustomTtsName();

    Path getTtsBasePath();

    Path getVoiceLibraryPath();

    default long getTtsAutoLoadDelayMillis() {
        return 1_000L;
    }

    /**
     * Whether autoregressive backends may synthesise incrementally. Streaming decodes every
     * generated chunk separately, which is expensive for autoregressive models on CPU; turning it
     * off synthesises one sentence at a time to completion before playback, which removes
     * intra-sentence stalls at the cost of a later first audio. The default keeps streaming.
     */
    default boolean isTtsStreamingSynthesisEnabled() {
        return true;
    }

    /** @return {@code "local"} or {@code "cloud"}; the backend the player selected. */
    default String getTtsBackendMode() {
        return "local";
    }

    /** Empty means "use the provider catalog default". */
    default String getCloudProviderId() {
        return "";
    }

    /** Empty means "use the provider catalog default endpoint". */
    default String getCloudEndpoint() {
        return "";
    }

    /** Empty means "use the provider catalog default voice". */
    default String getCloudVoice() {
        return "";
    }
}