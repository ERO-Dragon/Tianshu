package com.rheinmetal.tianshu.function.asr.audio;

import com.rheinmetal.tianshu.function.asr.recognition.AsrSpeechSegmenter;
import com.rheinmetal.tianshu.function.asr.recognition.AsrVadSpeechSegmenter;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.io.ByteArrayOutputStream;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class AudioCaptureService {
    private final IAudioBridge audioBridge;
    private final IGameEnvironment env;
    private volatile AsrSpeechSegmenter speechSegmenter;
    private volatile AudioFrameProcessor frameProcessor = AudioFrameProcessor.identity();
    private volatile ByteArrayOutputStream pttBuffer;

    public AudioCaptureService(IAudioBridge audioBridge, IGameEnvironment env) {
        this(audioBridge, env, AsrSpeechSegmenter.disabled());
    }

    public AudioCaptureService(IAudioBridge audioBridge, IGameEnvironment env, AsrSpeechActivityListener speechActivityListener) {
        this(audioBridge, env, new AsrVadSpeechSegmenter(speechActivityListener));
    }

    public AudioCaptureService(IAudioBridge audioBridge, IGameEnvironment env, AsrSpeechSegmenter speechSegmenter) {
        this.audioBridge = audioBridge;
        this.env = env;
        this.speechSegmenter = speechSegmenter == null ? AsrSpeechSegmenter.disabled() : speechSegmenter;
    }

    public void setSpeechSegmenter(AsrSpeechSegmenter speechSegmenter) {
        AsrSpeechSegmenter previous = this.speechSegmenter;
        if (previous != null) {
            previous.reset();
        }
        this.speechSegmenter = speechSegmenter == null ? AsrSpeechSegmenter.disabled() : speechSegmenter;
    }

    public void setFrameProcessor(AudioFrameProcessor frameProcessor) {
        this.frameProcessor = frameProcessor == null ? AudioFrameProcessor.identity() : frameProcessor;
    }

    public void startPttCapture(long sessionId) {
        stopStreamCapture();
        speechSegmenter.reset();
        frameProcessor.reset();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        pttBuffer = buffer;
        audioBridge.startStreamRecording(chunk -> {
            byte[] processed = processChunk(chunk);
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
        startStreamCapture(sessionId, (chunk, ignored) -> consumer.accept(chunk));
    }

    public void startStreamCapture(long sessionId, BiConsumer<byte[], AsrSpeechSegmenter.Decision> consumer) {
        frameProcessor.reset();
        speechSegmenter.start(sessionId);
        audioBridge.startStreamRecording(chunk -> {
            byte[] processed = processChunk(chunk);
            if (processed != null && processed.length > 0) {
                AsrSpeechSegmenter.Decision decision = speechSegmenter.accept(processed);
                consumer.accept(processed, decision);
            }
        });
    }

    public void stopStreamCapture() {
        audioBridge.stopStreamRecording();
        speechSegmenter.reset();
    }

    public void stopAll() {
        attemptCleanup("tianshu.asr.audio.ptt_stop_failed", audioBridge::stopRecording);
        attemptCleanup("tianshu.asr.audio.stream_stop_failed", audioBridge::stopStreamRecording);
        speechSegmenter.reset();
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

    private byte[] processChunk(byte[] chunk) {
        return frameProcessor.process(chunk);
    }
}
