package com.rheinmetal.tianshu.intake.settings;

import java.nio.file.Path;

public interface IntakeConfiguration {
    boolean isAsrEnabled();

    String getSelectedMicName();

    boolean isAsrHighPassFilterEnabled();

    String getCustomAsrName();

    Path getAsrBasePath();

    /**
     * How many seconds of one held utterance to keep; {@code 0} means no limit.
     *
     * <p>Product decision: holding the talk key does not truncate what the player says, so the default is unlimited. A
     * server or a player who wants a guard can set a limit, and hitting it is reported rather than silently dropping the
     * rest — a truncated sentence looks exactly like a microphone that failed.</p>
     */
    default int getMaxUtteranceSeconds() {
        return 0;
    }

    default Path getAsrModelPath() {
        String modelName = getCustomAsrName();
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        Path modelRoot = getAsrBasePath().resolve("model").normalize();
        Path modelPath = modelRoot.resolve(modelName.trim()).normalize();
        if (!modelPath.startsWith(modelRoot)) {
            throw new IllegalArgumentException("ASR model selection escapes the model root");
        }
        return modelPath;
    }
}
