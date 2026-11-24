package com.rheinmetal.tianshu.api;

import java.util.List;
import java.util.function.Consumer;

public interface IAudioBridge {

    void ensureHardwareRunning();

    void releaseCaptureHardware();

    void startRecording();

    byte[] stopRecording();

    void startStreamRecording(Consumer<byte[]> onAudioChunk);

    void stopStreamRecording();

    void startTtsPlayback(int sampleRate);

    default void startTtsPlayback(String requestId, int sampleRate) {
        startTtsPlayback(sampleRate);
    }

    void feedTtsAudio(byte[] audio);

    default void feedTtsAudio(String requestId, byte[] audio) {
        feedTtsAudio(audio);
    }

    void finishTtsPlayback();

    default void finishTtsPlayback(String requestId) {
        finishTtsPlayback();
    }

    /**
     * How one utterance ended.
     *
     * <p>A device that cannot open, write or drain is a failure of the request, not a silent sentence: the module above
     * has to be able to tell "it played" from "it did not", or a session is marked spoken when nothing was heard.</p>
     */
    enum PlaybackOutcome {
        FINISHED,
        FAILED
    }

    /**
     * Registers how the utterance that is about to start ended.
     *
     * <p>Called exactly once for a finished or failed utterance, and never for an aborted one — an interruption must not
     * report completion. The default keeps the legacy {@link Runnable} registration working for implementations that
     * predate the outcome: those only ever see {@link PlaybackOutcome#FINISHED}.</p>
     */
    default void setOnPlaybackFinished(Consumer<PlaybackOutcome> callback) {
    }

    /** Legacy registration: receives the completion, with failures indistinguishable from a finished utterance. */
    default void setOnPlaybackFinished(Runnable callback) {
        if (callback != null) {
            setOnPlaybackFinished((Consumer<PlaybackOutcome>) outcome -> callback.run());
        }
    }

    void stopTtsPlayback();

    default void stopTtsPlayback(String requestId) {
        stopTtsPlayback();
    }

    void playAudio(byte[] audioData, int sampleRate);

    void stopPlayback();

    boolean isRecording();

    boolean isPlaying();

    boolean isStreaming();

    List<String> getAvailableMicNames();

    String getCurrentMicName();

    void selectMic(String micName);

    void switchToNextMic();

    void shutdown();
}
