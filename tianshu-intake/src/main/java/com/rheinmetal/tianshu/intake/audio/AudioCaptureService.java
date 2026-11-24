package com.rheinmetal.tianshu.intake.audio;

import com.rheinmetal.tianshu.intake.recognition.AsrSpeechSegmenter;
import com.rheinmetal.tianshu.intake.recognition.AsrVadSpeechSegmenter;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The microphone boundary: one capture generation at a time, and the processed audio of that generation.
 *
 * <p><b>Sealed:</b> the runtime only starts push-to-talk capture. The streaming entry points
 * ({@link #startStreamCapture}, {@link #setSpeechSegmenter}, {@link #resetStreamSegmentBoundary}) exist for the
 * continuous-input extension point; their only current callers are tests, and no setting can reach them.</p>
 */
public final class AudioCaptureService {
    /**
     * One push-to-talk utterance is capped at a minute of 16 kHz mono 16-bit audio.
     *
     * <p>A hold-to-talk key can be held indefinitely, and the buffer grew with the hold: an hour is ~115 MB, and the
     * copy on release doubles the peak. The cap turns that into a known bound; what was said is kept, and the cut is
     * reported so it is not mistaken for a silent microphone.</p>
     */
    static final int MAX_PTT_BYTES = 16_000 * 2 * 60;
    private final IAudioBridge audioBridge;
    private final IGameEnvironment env;
    private volatile AsrSpeechSegmenter speechSegmenter;
    private volatile AudioFrameProcessor frameProcessor = AudioFrameProcessor.identity();
    private volatile ByteArrayOutputStream pttBuffer;
    private final AtomicLong captureGeneration = new AtomicLong();
    private volatile long activeStreamSessionId;

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

    /**
     * Starts one push-to-talk capture.
     *
     * <p>The turn id stays in the controller: capture only needs a generation counter to drop late chunks, so it
     * does not take an id it would never use.</p>
     */
    public void startPttCapture() {
        stopStreamCapture();
        speechSegmenter.reset();
        frameProcessor.reset();
        long generation = captureGeneration.incrementAndGet();
        activeStreamSessionId = 0L;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        pttBuffer = buffer;
        audioBridge.startStreamRecording(chunk -> {
            if (!isCurrentGeneration(generation)) {
                return;
            }
            byte[] processed = processChunk(chunk);
            if (processed != null && processed.length > 0) {
                appendCapped(buffer, processed);
            }
        });
    }

    public byte[] stopPttCapture() {
        audioBridge.stopStreamRecording();
        captureGeneration.incrementAndGet();
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
        audioBridge.startStreamRecording(chunk -> {
            if (!isCurrentGeneration(generation)) {
                return;
            }
            byte[] processed = processChunk(chunk);
            if (processed != null && processed.length > 0) {
                AsrSpeechSegmenter.Decision decision = speechSegmenter.accept(processed);
                if (!isCurrentGeneration(generation)) {
                    return;
                }
                consumer.accept(processed, decision);
            }
        });
    }

    public void stopStreamCapture() {
        captureGeneration.incrementAndGet();
        activeStreamSessionId = 0L;
        audioBridge.stopStreamRecording();
        speechSegmenter.reset();
    }

    public void resetStreamSegmentBoundary() {
        speechSegmenter.resetSegmentBoundary();
    }

    public void stopAll() {
        captureGeneration.incrementAndGet();
        activeStreamSessionId = 0L;
        attemptCleanup("asr.audio.ptt_stop_failed", audioBridge::stopRecording);
        attemptCleanup("asr.audio.stream_stop_failed", audioBridge::stopStreamRecording);
        speechSegmenter.reset();
        pttBuffer = null;
    }

    public void releaseHardware() {
        stopAll();
        attemptCleanup("asr.audio.hardware_release_failed", audioBridge::releaseCaptureHardware);
    }

    private void attemptCleanup(String diagnosticCode, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | LinkageError failure) {
            env.error(diagnosticCode, failure);
        }
    }

    /** Appends up to the utterance cap, reporting the cut once so a truncated hold is not read as a dead microphone. */
    private void appendCapped(ByteArrayOutputStream buffer, byte[] chunk) {
        synchronized (buffer) {
            int room = MAX_PTT_BYTES - buffer.size();
            if (room <= 0) {
                return;
            }
            int write = Math.min(room, chunk.length);
            buffer.write(chunk, 0, write);
            if (write < chunk.length) {
                env.warn("push-to-talk hit its single-utterance limit of " + MAX_PTT_BYTES
                        + " bytes; the rest of the hold was not recorded");
            }
        }
    }

    private byte[] processChunk(byte[] chunk) {
        return frameProcessor.process(chunk);
    }

    private boolean isCurrentGeneration(long generation) {
        return captureGeneration.get() == generation;
    }
}
