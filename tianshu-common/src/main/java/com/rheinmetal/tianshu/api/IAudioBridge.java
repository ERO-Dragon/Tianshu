package com.rheinmetal.tianshu.api;

import java.util.function.Consumer;

public interface IAudioBridge {

    void ensureHardwareRunning();

    void startRecording();

    byte[] stopRecording();

    void startStreamRecording(Consumer<byte[]> onAudioChunk);

    void stopStreamRecording();

    void startTtsPlayback(int sampleRate);

    void feedTtsAudio(byte[] audio);

    void finishTtsPlayback();

    void stopTtsPlayback();

    void playAudio(byte[] audioData, int sampleRate);

    void stopPlayback();

    boolean isRecording();

    boolean isPlaying();

    boolean isStreaming();

    String getCurrentMicName();

    void switchToNextMic();

    void shutdown();
}
