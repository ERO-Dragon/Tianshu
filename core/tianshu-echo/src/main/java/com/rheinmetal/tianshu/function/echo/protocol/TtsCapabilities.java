package com.rheinmetal.tianshu.function.echo.protocol;

/**
 * The capabilities the speech-output module provides.
 *
 * <p>Owned here rather than in api: a capability id is the producer's contract. A consumer that hard-depends
 * on this module imports this class; an optional consumer uses the id as a string in its bridge.</p>
 */
public final class TtsCapabilities {

    public static final String TTS_SPEAK = "TTS_SPEAK";
    public static final String TTS_SYNTHESIZE = "TTS_SYNTHESIZE";
    public static final String TTS_AUDIO_ACK = "TTS_AUDIO_ACK";
    public static final String TTS_CONTROL = "TTS_CONTROL";

    private TtsCapabilities() {
    }
}
