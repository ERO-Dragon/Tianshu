package com.rheinmetal.tianshu.client.audio;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The text-to-speech output channel: one line, one utterance at a time, plus the metrics that describe it.
 *
 * <p>Extracted from {@code AudioManager}, which carried the microphone, the speakers and this channel in one class.
 * The behaviour is unchanged by the move: an utterance owns the line from {@link #start} to {@link #finish},
 * {@link #abort} drops the completion hook so an interrupted sentence never reports completion, and a call naming an
 * older request is ignored once a newer one owns the line.</p>
 */
final class TtsChannel {

    private final LogSink logSink;
    private final DiagnosticSink diagnostics;
    private final LineOpener lineOpener;
    /** The hook of the utterance that currently owns the line; null when none is pending. */
    private final AtomicReference<java.util.function.Consumer<com.rheinmetal.tianshu.api.IAudioBridge.PlaybackOutcome>>
            onPlaybackFinished = new AtomicReference<>();
    /** Set when the device refused to open, write or drain this utterance. */
    private volatile boolean failed;

    private volatile SourceDataLine line;
    private volatile String requestId = "";
    private volatile long openedAtNanos;
    private volatile long lastWriteAtNanos;
    private volatile long writeNanos;
    private volatile long drainNanos;
    private volatile long writeCount;
    private volatile long bytesWritten;
    private volatile long maxWriteGapMillis;
    private volatile int minAvailableBytes = Integer.MAX_VALUE;

    /**
     * Opens the output line for one utterance.
     *
     * <p>A seam, not a feature: the failure paths this channel has to report are exactly the ones a real device refuses
     * to produce on demand, so a test supplies an opener that fails instead of waiting for a broken sound card.</p>
     */
    interface LineOpener {
        SourceDataLine open(AudioFormat format) throws LineUnavailableException;
    }

    private static final LineOpener SYSTEM_LINES = format -> {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("no line for " + format.getSampleRate());
        }
        SourceDataLine opened = (SourceDataLine) AudioSystem.getLine(info);
        opened.open(format);
        opened.start();
        return opened;
    };

    TtsChannel(LogSink logSink, DiagnosticSink diagnostics) {
        this(logSink, diagnostics, SYSTEM_LINES);
    }

    TtsChannel(LogSink logSink, DiagnosticSink diagnostics, LineOpener opener) {
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
        this.diagnostics = diagnostics == null ? DiagnosticSink.NOOP : diagnostics;
        this.lineOpener = opener == null ? SYSTEM_LINES : opener;
    }

    /** The id of the utterance that owns the line; blank when none has started. */
    String requestId() {
        return requestId;
    }

    void setOnPlaybackFinished(
            java.util.function.Consumer<com.rheinmetal.tianshu.api.IAudioBridge.PlaybackOutcome> callback) {
        onPlaybackFinished.set(callback);
    }

    /**
     * Opens the line for a new utterance, keeping the completion hook the caller registered for it.
     *
     * <p>This used to begin with an abort, which nulls the hook. The caller registers the hook immediately before
     * starting, so a graceful finish never reported completion and a multi-sentence reply stalled on its first
     * sentence. Dropping the hook belongs to an explicit interrupt ({@link #abort}), which is exactly what "an aborted
     * utterance must never report playback completion" asks for.</p>
     */
    void start(String requestId, int sampleRate) {
        close();
        this.requestId = requestId == null ? "" : requestId.trim();
        failed = false;
        openedAtNanos = 0L;
        lastWriteAtNanos = 0L;
        writeNanos = 0L;
        drainNanos = 0L;
        writeCount = 0L;
        bytesWritten = 0L;
        maxWriteGapMillis = 0L;
        minAvailableBytes = Integer.MAX_VALUE;
        try {
            SourceDataLine opened = lineOpener.open(new AudioFormat(sampleRate, 16, 1, true, false));
            line = opened;
            openedAtNanos = System.nanoTime();
            logSink.info("audio.tts_channel.opened sampleRate=" + sampleRate);
        } catch (LineUnavailableException unavailable) {
            logSink.error("audio.tts_channel.open_failed", unavailable);
            deliver(com.rheinmetal.tianshu.api.IAudioBridge.PlaybackOutcome.FAILED);
        }
    }

    void feed(String requestId, byte[] audioData) {
        if (!owns(requestId)) {
            // A chunk from an interrupted utterance: writing it would splice the old sentence into the new one.
            return;
        }
        SourceDataLine open = line;
        if (open == null || !open.isOpen()) {
            return;
        }
        if (audioData == null || audioData.length == 0) {
            return;
        }
        try {
            long writeStarted = System.nanoTime();
            open.write(audioData, 0, audioData.length);
            long now = System.nanoTime();
            if (lastWriteAtNanos > 0L) {
                maxWriteGapMillis = Math.max(maxWriteGapMillis,
                        Math.max(0L, (writeStarted - lastWriteAtNanos) / 1_000_000L));
            }
            lastWriteAtNanos = now;
            writeNanos += Math.max(0L, now - writeStarted);
            writeCount++;
            bytesWritten += audioData.length;
            try {
                minAvailableBytes = Math.min(minAvailableBytes, open.available());
            } catch (IllegalStateException closedMeanwhile) {
                // The line was closed while we measured it; a failed write reports the state itself.
            }
        } catch (Exception writeFailure) {
            // A failed write is missing audio for this request; report it once and stop claiming success.
            if (!failed) {
                failed = true;
                logSink.error("audio.tts_channel.write_failed", writeFailure);
            }
        }
    }

    void finish(String requestId) {
        if (!owns(requestId)) {
            // The late end of an aborted utterance must not close the line the new one is using.
            return;
        }
        SourceDataLine open = line;
        if (open != null) {
            try {
                long drainStarted = System.nanoTime();
                open.drain();
                drainNanos = Math.max(0L, System.nanoTime() - drainStarted);
            } catch (Exception drainFailure) {
                failed = true;
                logSink.error("audio.tts_channel.drain_failed", drainFailure);
            }
        }
        publishMetrics(requestId);
        // Detach before closing so the hook is delivered exactly once; an abort takes the other branch and drops it.
        deliver(failed
                ? com.rheinmetal.tianshu.api.IAudioBridge.PlaybackOutcome.FAILED
                : com.rheinmetal.tianshu.api.IAudioBridge.PlaybackOutcome.FINISHED);
    }

    /** Hands the outcome to whoever registered for it, at most once. */
    private void deliver(com.rheinmetal.tianshu.api.IAudioBridge.PlaybackOutcome outcome) {
        var finished = onPlaybackFinished.getAndSet(null);
        close();
        if (finished != null) {
            finished.accept(outcome);
        }
    }

    /** Stops the named utterance only; a late abort for a replaced request is a no-op. */
    void stop(String requestId) {
        if (!owns(requestId)) {
            return;
        }
        abort();
    }

    /** Aborts whatever is playing; an aborted utterance must never report playback completion. */
    void abort() {
        onPlaybackFinished.set(null);
        close();
    }

    /** Closes the line without touching completion bookkeeping. */
    void close() {
        SourceDataLine open = line;
        line = null;
        if (open == null) {
            return;
        }
        try {
            open.stop();
            open.close();
        } catch (Exception closeFailure) {
            logSink.error("audio.tts_channel.close_failed", closeFailure);
        }
    }

    /**
     * Whether {@code requestId} is the utterance that currently owns the line.
     *
     * <p>A blank id on either side means there is nothing to tell apart: the parameterless calls take that route, and
     * so does a caller that never recorded an id — it must still get its completion, which is the only report it will
     * ever receive. A non-blank mismatch is the dangerous case: the late tail of a replaced utterance.</p>
     */
    private boolean owns(String requestId) {
        if (requestId == null || requestId.isBlank() || this.requestId.isBlank()) {
            return true;
        }
        return requestId.trim().equals(this.requestId);
    }

    private void publishMetrics(String requestId) {
        if (requestId == null || requestId.isBlank() || openedAtNanos <= 0L) {
            return;
        }
        long endedAtNanos = System.nanoTime();
        diagnostics.publish(DiagnosticEvent.now(
                ProtocolModuleIds.PLATFORM_AUDIO,
                "AUDIO_LINE_METRICS",
                DiagnosticSeverity.DEBUG,
                DiagnosticPrivacy.REDACTED,
                metricAttributes(requestId, openedAtNanos, endedAtNanos, writeNanos, drainNanos, writeCount,
                        bytesWritten, maxWriteGapMillis, minAvailableBytes)
        ));
    }

    /** The attributes of one line's lifetime; package-visible so the shape is testable without a device. */
    static Map<String, String> metricAttributes(
            String requestId,
            long openedAtNanos,
            long endedAtNanos,
            long writeNanos,
            long drainNanos,
            long writeCount,
            long bytesWritten,
            long maxWriteGapMillis,
            int minAvailableBytes
    ) {
        String normalizedRequestId = requestId == null ? "" : requestId.trim();
        long lineOpenMillis = Math.max(0L, endedAtNanos - openedAtNanos) / 1_000_000L;
        return Map.of(
                "requestId", normalizedRequestId,
                "lineOpenMillis", Long.toString(lineOpenMillis),
                "writeMillis", Long.toString(Math.max(0L, writeNanos) / 1_000_000L),
                "drainMillis", Long.toString(Math.max(0L, drainNanos) / 1_000_000L),
                "writeCount", Long.toString(Math.max(0L, writeCount)),
                "bytesWritten", Long.toString(Math.max(0L, bytesWritten)),
                "maxWriteGapMillis", Long.toString(Math.max(0L, maxWriteGapMillis)),
                "minAvailableBytes", Integer.toString(Math.max(0, minAvailableBytes == Integer.MAX_VALUE ? 0 : minAvailableBytes)),
                "writeGapObserved", Boolean.toString(maxWriteGapMillis > 0L && writeCount > 1L)
        );
    }
}