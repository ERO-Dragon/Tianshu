package com.rheinmetal.tianshu.function.asr.audio;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

public final class AudioCaptureService {
    private final IAudioBridge audioBridge;
    private final IGameEnvironment env;
    private final AsrSpeechActivityDetector speechActivityDetector;
    private volatile AudioFrameProcessor frameProcessor = AudioFrameProcessor.identity();
    private volatile ByteArrayOutputStream pttBuffer;

    public AudioCaptureService(IAudioBridge audioBridge, IGameEnvironment env) {
        this(audioBridge, env, AsrSpeechActivityListener.noop());
    }

    public AudioCaptureService(IAudioBridge audioBridge, IGameEnvironment env, AsrSpeechActivityListener speechActivityListener) {
        this.audioBridge = audioBridge;
        this.env = env;
        this.speechActivityDetector = new AsrSpeechActivityDetector(speechActivityListener);
    }

    public void setFrameProcessor(AudioFrameProcessor frameProcessor) {
        this.frameProcessor = frameProcessor == null ? AudioFrameProcessor.identity() : frameProcessor;
    }

    public void startPttCapture(long sessionId) {
        stopStreamCapture();
        frameProcessor.reset();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        pttBuffer = buffer;
        audioBridge.startStreamRecording(chunk -> {
            byte[] processed = processChunk(chunk, false);
            if (processed != null && processed.length > 0) {
                synchronized (buffer) {
                    buffer.write(processed, 0, processed.length);
                }
            }
        });
    }

    public byte[] stopPttCapture() {
        audioBridge.stopStreamRecording();
        ByteArrayOutputStream buffer = pttBuffer;
        pttBuffer = null;
        if (buffer == null) {
            return new byte[0];
        }
        synchronized (buffer) {
            return buffer.toByteArray();
        }
    }

    public void startStreamCapture(long sessionId, Consumer<byte[]> consumer) {
        frameProcessor.reset();
        speechActivityDetector.start(sessionId);
        audioBridge.startStreamRecording(chunk -> {
            byte[] processed = processChunk(chunk, true);
            if (processed != null && processed.length > 0) {
                consumer.accept(processed);
            }
        });
    }

    public void stopStreamCapture() {
        audioBridge.stopStreamRecording();
        speechActivityDetector.stop();
    }

    public void stopAll() {
        attemptCleanup("tianshu.asr.audio.ptt_stop_failed", audioBridge::stopRecording);
        attemptCleanup("tianshu.asr.audio.stream_stop_failed", audioBridge::stopStreamRecording);
        speechActivityDetector.stop();
        pttBuffer = null;
    }

    public void releaseHardware() {
        stopAll();
        attemptCleanup("tianshu.asr.audio.hardware_release_failed", audioBridge::releaseCaptureHardware);
    }

    private void attemptCleanup(String diagnosticCode, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | LinkageError failure) {
            env.error(diagnosticCode, failure);
        }
    }

    private byte[] processChunk(byte[] chunk, boolean detectSpeechActivity) {
        byte[] processed = frameProcessor.process(chunk);
        if (detectSpeechActivity && processed != null && processed.length > 0) {
            speechActivityDetector.accept(processed);
        }
        return processed;
    }
}
