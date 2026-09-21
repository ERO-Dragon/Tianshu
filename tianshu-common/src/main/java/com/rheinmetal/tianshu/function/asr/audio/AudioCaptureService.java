package com.rheinmetal.tianshu.function.asr.audio;

import com.rheinmetal.tianshu.function.asr.recognition.AsrSpeechSegmenter;
import com.rheinmetal.tianshu.function.asr.recognition.AsrVadSpeechSegmenter;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class AudioCaptureService {
    private final IAudioBridge audioBridge;
    private final IGameEnvironment env;
    private volatile AsrSpeechSegmenter speechSegmenter;
    private volatile AudioFrameProcessor frameProcessor = AudioFrameProcessor.identity();
    private volatile ByteArrayOutputStream pttBuffer;
    private final AsrAudioDiagnostics diagnostics;
    private final AtomicLong captureGeneration = new AtomicLong();
    private volatile long activeStreamSessionId;

    public AudioCaptureService(IAudioBridge audioBridge, IGameEnvironment env) {
        this(audioBridge, env, AsrSpeechSegmenter.disabled(), null);
    }

    public AudioCaptureService(IAudioBridge audioBridge, IGameEnvironment env, AsrSpeechActivityListener speechActivityListener) {
        this(audioBridge, env, new AsrVadSpeechSegmenter(speechActivityListener), null);
    }

    public AudioCaptureService(IAudioBridge audioBridge, IGameEnvironment env, AsrSpeechSegmenter speechSegmenter) {
        this(audioBridge, env, speechSegmenter, null);
    }

    public AudioCaptureService(IAudioBridge audioBridge, IGameEnvironment env,
                               AsrSpeechSegmenter speechSegmenter, AsrAudioDiagnostics diagnostics) {
        this.audioBridge = audioBridge;
        this.env = env;
        this.speechSegmenter = speechSegmenter == null ? AsrSpeechSegmenter.disabled() : speechSegmenter;
        this.diagnostics = diagnostics;
    }

    public void setSpeechSegmenter(AsrSpeechSegmenter speechSegmenter) {
        AsrSpeechSegmenter previous = this.speechSegmenter;
        if (previous != null) {
            previous.reset();
        }
        AsrSpeechSegmenter replacement = speechSegmenter == null ? AsrSpeechSegmenter.disabled() : speechSegmenter;
        this.speechSegmenter = replacement;
        long sessionId = activeStreamSessionId;
        if (sessionId > 0L) {
            replacement.start(sessionId);
        }
    }

    public void setFrameProcessor(AudioFrameProcessor frameProcessor) {
        this.frameProcessor = frameProcessor == null ? AudioFrameProcessor.identity() : frameProcessor;
    }

    public void startPttCapture(long sessionId) {
        stopStreamCapture();
        speechSegmenter.reset();
        frameProcessor.reset();
        long generation = captureGeneration.incrementAndGet();
        activeStreamSessionId = 0L;
        diagnosticsBegin(sessionId);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        pttBuffer = buffer;
        audioBridge.startStreamRecording(chunk -> {
            if (!isCurrentGeneration(generation)) {
                return;
            }
            byte[] raw = rawForDiagnostics(chunk);
            byte[] processed = processChunk(chunk);
            if (processed != null && processed.length > 0) {
                recordDiagnostics(raw, processed);
                synchronized (buffer) {
                    buffer.write(processed, 0, processed.length);
                }
            }
        });
    }

    public byte[] stopPttCapture() {
        audioBridge.stopStreamRecording();
        captureGeneration.incrementAndGet();
        diagnosticsEnd();
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
        long generation = captureGeneration.incrementAndGet();
        activeStreamSessionId = Math.max(0L, sessionId);
        frameProcessor.reset();
        speechSegmenter.start(sessionId);
        diagnosticsBegin(sessionId);
        audioBridge.startStreamRecording(chunk -> {
            if (!isCurrentGeneration(generation)) {
                return;
            }
            byte[] raw = rawForDiagnostics(chunk);
            byte[] processed = processChunk(chunk);
            if (processed != null && processed.length > 0) {
                AsrSpeechSegmenter.Decision decision = speechSegmenter.accept(processed);
                if (!isCurrentGeneration(generation)) {
                    return;
                }
                recordDiagnostics(raw, processed);
                consumer.accept(processed, decision);
            }
        });
    }

    public void stopStreamCapture() {
        captureGeneration.incrementAndGet();
        activeStreamSessionId = 0L;
        audioBridge.stopStreamRecording();
        speechSegmenter.reset();
        diagnosticsEnd();
    }

    public void resetStreamSegmentBoundary() {
        speechSegmenter.resetSegmentBoundary();
    }

    public void stopAll() {
        captureGeneration.incrementAndGet();
        activeStreamSessionId = 0L;
        attemptCleanup("tianshu.asr.audio.ptt_stop_failed", audioBridge::stopRecording);
        attemptCleanup("tianshu.asr.audio.stream_stop_failed", audioBridge::stopStreamRecording);
        speechSegmenter.reset();
        diagnosticsEnd();
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

    private void diagnosticsBegin(long sessionId) {
        if (diagnostics != null) {
            diagnostics.beginCapture(sessionId);
        }
    }

    private void diagnosticsEnd() {
        if (diagnostics != null) {
            diagnostics.endCapture();
        }
    }

    private void recordDiagnostics(byte[] raw, byte[] processed) {
        if (diagnostics != null) {
            diagnostics.record(raw, processed, speechSegmenter.activitySnapshot());
        }
    }

    private byte[] rawForDiagnostics(byte[] chunk) {
        return diagnostics != null && diagnostics.isEnabled() && chunk != null
                ? chunk.clone()
                : chunk;
    }

    private boolean isCurrentGeneration(long generation) {
        return captureGeneration.get() == generation;
    }
}
