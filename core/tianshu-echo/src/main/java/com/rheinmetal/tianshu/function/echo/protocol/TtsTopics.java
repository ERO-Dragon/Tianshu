package com.rheinmetal.tianshu.function.echo.protocol;

/**
 * The topics the speech-output module produces.
 *
 * <p>Owned here rather than in the kernel: a feature's topic name changes with the feature, not with the host.</p>
 */
public final class TtsTopics {

    public static final String TTS_PLAYBACK = "TTS.PLAYBACK";
    public static final String TTS_REQUEST_STATUS = "TTS.REQUEST_STATUS";

    private TtsTopics() {
    }
}
