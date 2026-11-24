package com.rheinmetal.tianshu.client.audio;

import com.rheinmetal.tianshu.api.IAudioBridge.PlaybackOutcome;
import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A device that cannot play is a failed sentence, not a finished one.
 *
 * <p>Every failure path used to fall through to the completion hook, so the module above marked the sentence spoken and
 * queued the next one: to the player a sentence vanished, to the agent it had been delivered.</p>
 */
class TtsChannelOutcomeTest {

    @Test
    void aLineThatCannotOpenReportsFailureImmediately() {
        TtsChannel channel = new TtsChannel(LogSink.NOOP, DiagnosticSink.NOOP, format -> {
            throw new LineUnavailableException("no device");
        });
        List<PlaybackOutcome> outcomes = new ArrayList<>();
        channel.setOnPlaybackFinished(outcomes::add);

        channel.start("request-1", 24_000);

        assertEquals(List.of(PlaybackOutcome.FAILED), outcomes,
                "the caller has to learn this sentence did not play, or the queue waits for a hook that never comes");
    }

    @Test
    void aGracefulFinishReportsFinished() {
        TtsChannel channel = new TtsChannel(LogSink.NOOP, DiagnosticSink.NOOP, acceptingLine());
        List<PlaybackOutcome> outcomes = new ArrayList<>();
        channel.setOnPlaybackFinished(outcomes::add);

        channel.start("request-1", 24_000);
        channel.feed("request-1", new byte[64]);
        channel.finish("request-1");

        assertEquals(List.of(PlaybackOutcome.FINISHED), outcomes);
    }

    @Test
    void anInterruptedUtteranceStillReportsNothing() {
        TtsChannel channel = new TtsChannel(LogSink.NOOP, DiagnosticSink.NOOP, acceptingLine());
        AtomicInteger delivered = new AtomicInteger();
        channel.setOnPlaybackFinished(outcome -> delivered.incrementAndGet());

        channel.start("request-1", 24_000);
        channel.abort();
        channel.finish("request-1");

        assertEquals(0, delivered.get(), "an interruption must not be reported as a finished sentence");
    }

    @Test
    void aLineThatCannotBeOpenedLeavesTheRequestUnfinishedSoTheCallerSeesTheFailure() {
        TtsChannel channel = new TtsChannel(LogSink.NOOP, DiagnosticSink.NOOP, format -> {
            throw new LineUnavailableException("busy");
        });
        List<PlaybackOutcome> outcomes = new ArrayList<>();
        channel.setOnPlaybackFinished(outcomes::add);

        channel.start("request-1", 24_000);
        // The module may still finish the utterance after the failure; it must not receive a second outcome.
        channel.finish("request-1");

        assertEquals(1, outcomes.size(), "one utterance reports exactly one outcome: " + outcomes);
        assertTrue(outcomes.contains(PlaybackOutcome.FAILED));
    }

    /** A line that accepts writes without a sound card, so the success path is testable on any machine. */
    private static TtsChannel.LineOpener acceptingLine() {
        return new TtsChannel.LineOpener() {
            @Override
            public javax.sound.sampled.SourceDataLine open(AudioFormat format) {
                return new SilentLine();
            }
        };
    }

    /** The smallest stand-in for an open line: every call succeeds and no audio is produced. */
    private static final class SilentLine implements javax.sound.sampled.SourceDataLine {

        @Override
        public void open(AudioFormat format, int bufferSize) {
        }

        @Override
        public void open(AudioFormat format) {
        }

        @Override
        public void open() {
        }

        @Override
        public int write(byte[] data, int offset, int length) {
            return length;
        }

        @Override
        public void drain() {
        }

        @Override
        public void flush() {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public AudioFormat getFormat() {
            return new AudioFormat(24_000, 16, 1, true, false);
        }

        @Override
        public int getBufferSize() {
            return 1_024;
        }

        @Override
        public int available() {
            return 1_024;
        }

        @Override
        public int getFramePosition() {
            return 0;
        }

        @Override
        public long getLongFramePosition() {
            return 0L;
        }

        @Override
        public long getMicrosecondPosition() {
            return 0L;
        }

        @Override
        public float getLevel() {
            return 0.0F;
        }

        @Override
        public javax.sound.sampled.Line.Info getLineInfo() {
            return null;
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public javax.sound.sampled.Control[] getControls() {
            return new javax.sound.sampled.Control[0];
        }

        @Override
        public javax.sound.sampled.Control getControl(javax.sound.sampled.Control.Type control) {
            return null;
        }

        @Override
        public boolean isControlSupported(javax.sound.sampled.Control.Type control) {
            return false;
        }

        @Override
        public void addLineListener(javax.sound.sampled.LineListener listener) {
        }

        @Override
        public void removeLineListener(javax.sound.sampled.LineListener listener) {
        }
    }
}