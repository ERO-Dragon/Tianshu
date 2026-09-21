package com.rheinmetal.tianshu.function.asr.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsrAudioDiagnosticsTest {
    @Test
    void disabledDiagnosticsDoesNotRetainAudio() {
        AsrAudioDiagnostics diagnostics = new AsrAudioDiagnostics();

        diagnostics.record(pcm(0.4D, 160), pcm(0.2D, 160),
                new AsrSpeechActivitySnapshot(0.2D, 0.08D, 0.04D, true, 7L, 10L));

        AsrAudioDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        assertEquals(0, snapshot.sampleCount());
        assertFalse(snapshot.captureActive());
    }

    @Test
    void enabledDiagnosticsReturnsChronologicalWaveformAndVadMetadata() {
        AsrAudioDiagnostics diagnostics = new AsrAudioDiagnostics();
        diagnostics.setEnabled(true);
        diagnostics.beginCapture(7L);

        diagnostics.record(pcm(0.4D, 160), pcm(0.2D, 160),
                new AsrSpeechActivitySnapshot(0.2D, 0.08D, 0.04D, true, 7L, 10L));
        diagnostics.record(pcm(-0.3D, 160), pcm(-0.1D, 160),
                new AsrSpeechActivitySnapshot(0.1D, 0.07D, 0.03D, false, 7L, 20L));

        AsrAudioDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        assertEquals(2, snapshot.sampleCount());
        assertEquals(0.4F, snapshot.rawMax()[0], 0.01F);
        assertEquals(-0.3F, snapshot.rawMin()[1], 0.01F);
        assertEquals(0.07F, snapshot.startThreshold(), 0.001F);
        assertEquals(0.03F, snapshot.stopThreshold(), 0.001F);
        assertEquals(0.3D, snapshot.rawRms(), 0.01D);
        assertEquals(0.1D, snapshot.processedRms(), 0.01D);
        assertEquals(0.08F, snapshot.startThresholds()[0], 0.001F);
        assertEquals(0.07F, snapshot.startThresholds()[1], 0.001F);
        assertEquals(0.04F, snapshot.stopThresholds()[0], 0.001F);
        assertEquals(0.03F, snapshot.stopThresholds()[1], 0.001F);
        assertFalse(snapshot.speaking());
        assertTrue(snapshot.captureActive());
        assertEquals(7L, snapshot.sessionId());
    }

    @Test
    void historyIsBoundedToFiveSeconds() {
        AsrAudioDiagnostics diagnostics = new AsrAudioDiagnostics();
        diagnostics.setEnabled(true);
        diagnostics.beginCapture(7L);

        for (int index = 0; index < AsrAudioDiagnostics.HISTORY_BINS + 25; index++) {
            diagnostics.record(pcm(index / 1000.0D, AsrAudioDiagnostics.BIN_SAMPLES),
                    pcm(index / 2000.0D, AsrAudioDiagnostics.BIN_SAMPLES),
                    AsrSpeechActivitySnapshot.inactive());
        }

        AsrAudioDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        assertEquals(AsrAudioDiagnostics.HISTORY_BINS, snapshot.sampleCount());
        assertEquals(25.0F / 1000.0F, snapshot.rawMin()[0], 0.01F);
    }

    @Test
    void endingCaptureMarksTheLastVadObservationSilentWithoutDiscardingHistory() {
        AsrAudioDiagnostics diagnostics = new AsrAudioDiagnostics();
        diagnostics.setEnabled(true);
        diagnostics.beginCapture(7L);
        diagnostics.record(pcm(0.2D, 160), pcm(0.1D, 160),
                new AsrSpeechActivitySnapshot(0.1D, 0.08D, 0.04D, true, 7L, 10L));

        diagnostics.endCapture();

        AsrAudioDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        assertEquals(1, snapshot.sampleCount());
        assertFalse(snapshot.speaking());
        assertEquals(0.08D, snapshot.startThreshold(), 0.001D);
        assertFalse(snapshot.captureActive());
    }

    private static byte[] pcm(double amplitude, int samples) {
        byte[] audio = new byte[samples * 2];
        short value = (short) Math.round(Math.max(-1.0D, Math.min(1.0D, amplitude)) * Short.MAX_VALUE);
        for (int index = 0; index < audio.length; index += 2) {
            audio[index] = (byte) (value & 0xFF);
            audio[index + 1] = (byte) ((value >>> 8) & 0xFF);
        }
        return audio;
    }
}
