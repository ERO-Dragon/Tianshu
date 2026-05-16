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

    void feedTtsAudio(byte[] audio);

    void finishTtsPlayback();

    void setOnPlaybackFinished(Runnable callback);

    void stopTtsPlayback();

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
