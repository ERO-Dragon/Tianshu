package com.rheinmetal.tianshu.function.asr.recognition;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The detector announces a segment only after it has lasted the minimum segment length, so a
 * boundary is observed within the following frames rather than on a single frame.
 */
class AsrVadSpeechSegmenterTest {
    @Test
    void startsOnSpeechEndsOnceAfterAdaptiveSilenceAndWaitsForNextSpeech() {
        AsrVadSpeechSegmenter segmenter = newSegmenter();
        segmenter.start(42L);
        primeCalibration(segmenter);

        List<AsrSpeechSegmenter.Decision> decisions = new ArrayList<>();
        repeat(510, () -> decisions.add(segmenter.accept(pcm(0.02D, 160))));
        repeat(60, () -> decisions.add(segmenter.accept(pcm(0.0D, 160))));

        assertEquals(1, decisions.stream().filter(AsrSpeechSegmenter.Decision::startsSegment).count());
        assertEquals(1, decisions.stream().filter(AsrSpeechSegmenter.Decision::endsSegment).count());

        assertEquals(AsrSpeechSegmenter.Decision.START_SEGMENT, nextStart(segmenter));
    }

    @Test
    void resetWaitsForNewSpeechAndDoesNotEmitAnEmptyBoundary() {
        AsrVadSpeechSegmenter segmenter = newSegmenter();
        segmenter.start(42L);
        primeCalibration(segmenter);
        segmenter.accept(pcm(0.02D, 160));
        segmenter.reset();
        segmenter.start(43L);
        primeCalibration(segmenter);

        assertEquals(AsrSpeechSegmenter.Decision.CONTINUE, segmenter.accept(pcm(0.0D, 160)));
        assertEquals(AsrSpeechSegmenter.Decision.START_SEGMENT, nextStart(segmenter));
    }

    @Test
    void manualBoundaryResetsDetectorBeforeTheNextSpeechSegment() {
        AsrVadSpeechSegmenter segmenter = newSegmenter();
        segmenter.start(42L);
        primeCalibration(segmenter);

        assertEquals(AsrSpeechSegmenter.Decision.START_SEGMENT, nextStart(segmenter));
        segmenter.resetSegmentBoundary();

        assertEquals(AsrSpeechSegmenter.Decision.CONTINUE, segmenter.accept(pcm(0.0D, 160)));
        assertEquals(AsrSpeechSegmenter.Decision.START_SEGMENT, nextStart(segmenter));
    }

    private static AsrVadSpeechSegmenter newSegmenter() {
        return new AsrVadSpeechSegmenter((speaking, sessionId, occurredAtMillis) -> {
        });
    }

    /** Drives speech frames until a segment start is reported, or gives up after plenty of frames. */
    private AsrSpeechSegmenter.Decision nextStart(AsrVadSpeechSegmenter segmenter) {
        byte[] speech = pcm(0.02D, 160);
        for (int index = 0; index < 510; index++) {
            AsrSpeechSegmenter.Decision decision = segmenter.accept(speech);
            if (decision.startsSegment()) {
                return decision;
            }
        }
        return AsrSpeechSegmenter.Decision.CONTINUE;
    }

    /**
     * The detector measures the room for 500 ms before it decides anything, so every test lets that
     * opening measurement finish at a low level first. A frame here is 10 ms.
     */
    private void primeCalibration(AsrVadSpeechSegmenter segmenter) {
        byte[] quiet = pcm(0.002D, 160);
        for (int index = 0; index < 60; index++) {
            segmenter.accept(quiet);
        }
    }

    private void repeat(int count, Runnable runnable) {
        for (int index = 0; index < count; index++) {
            runnable.run();
        }
    }

    private byte[] pcm(double amplitude, int samples) {
        byte[] audio = new byte[samples * 2];
        short value = (short) Math.round(Math.max(-1.0D, Math.min(1.0D, amplitude)) * Short.MAX_VALUE);
        for (int index = 0; index < audio.length; index += 2) {
            audio[index] = (byte) (value & 0xFF);
            audio[index + 1] = (byte) ((value >>> 8) & 0xFF);
        }
        return audio;
    }
}
