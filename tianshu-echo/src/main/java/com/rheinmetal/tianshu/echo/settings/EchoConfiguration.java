package com.rheinmetal.tianshu.echo.settings;

import java.nio.file.Path;

public interface EchoConfiguration {
    boolean isTtsEnabled();

    String getCustomTtsName();

    Path getTtsBasePath();

    Path getVoiceLibraryPath();

    default long getTtsAutoLoadDelayMillis() {
        return 1_000L;
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