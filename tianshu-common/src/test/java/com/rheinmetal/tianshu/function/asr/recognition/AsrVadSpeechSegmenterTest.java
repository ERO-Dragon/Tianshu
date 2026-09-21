package com.rheinmetal.tianshu.function.asr.recognition;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsrVadSpeechSegmenterTest {
    @Test
    void startsOnSpeechEndsOnceAfterAdaptiveSilenceAndWaitsForNextSpeech() {
        AsrVadSpeechSegmenter segmenter = new AsrVadSpeechSegmenter((speaking, sessionId, occurredAtMillis) -> {
        });
        segmenter.start(42L);

        List<AsrSpeechSegmenter.Decision> decisions = new ArrayList<>();
        repeat(510, () -> decisions.add(segmenter.accept(pcm(0.02D, 160))));
        repeat(25, () -> decisions.add(segmenter.accept(pcm(0.0D, 160))));
        repeat(20, () -> decisions.add(segmenter.accept(pcm(0.0D, 160))));

        assertEquals(1, decisions.stream().filter(AsrSpeechSegmenter.Decision::startsSegment).count());
        assertEquals(1, decisions.stream().filter(AsrSpeechSegmenter.Decision::endsSegment).count());

        assertEquals(AsrSpeechSegmenter.Decision.START_SEGMENT, segmenter.accept(pcm(0.02D, 160)));
    }

    @Test
    void resetWaitsForNewSpeechAndDoesNotEmitAnEmptyBoundary() {
        AsrVadSpeechSegmenter segmenter = new AsrVadSpeechSegmenter((speaking, sessionId, occurredAtMillis) -> {
        });
        segmenter.start(42L);
        segmenter.accept(pcm(0.02D, 160));
        segmenter.reset();
        segmenter.start(43L);

        assertEquals(AsrSpeechSegmenter.Decision.CONTINUE, segmenter.accept(pcm(0.0D, 160)));
        assertEquals(AsrSpeechSegmenter.Decision.START_SEGMENT, segmenter.accept(pcm(0.02D, 160)));
    }

    @Test
    void manualBoundaryResetsDetectorBeforeTheNextSpeechSegment() {
        AsrVadSpeechSegmenter segmenter = new AsrVadSpeechSegmenter((speaking, sessionId, occurredAtMillis) -> {
        });
        segmenter.start(42L);

        assertEquals(AsrSpeechSegmenter.Decision.START_SEGMENT, firstSpeechDecision(segmenter));
        segmenter.resetSegmentBoundary();

        assertEquals(AsrSpeechSegmenter.Decision.CONTINUE, segmenter.accept(pcm(0.0D, 160)));
        assertEquals(AsrSpeechSegmenter.Decision.START_SEGMENT, segmenter.accept(pcm(0.02D, 160)));
    }

    private AsrSpeechSegmenter.Decision firstSpeechDecision(AsrVadSpeechSegmenter segmenter) {
        for (int index = 0; index < 510; index++) {
            AsrSpeechSegmenter.Decision decision = segmenter.accept(pcm(0.02D, 160));
            if (decision.startsSegment()) {
                return decision;
            }
        }
        return AsrSpeechSegmenter.Decision.CONTINUE;
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
