package com.rheinmetal.tianshu.function.echo.synthesis.moss;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MossFrameDegenerationGuardTest {
    private static final int PAD = 1024;

    @Test
    void repeatedCompleteFrameEndsTheUtteranceAfterTheConfiguredRun() {
        MossFrameDegenerationGuard guard = new MossFrameDegenerationGuard(3, 5, PAD);
        List<Integer> frame = List.of(10, 20, 30);

        assertNull(guard.accept(frame));
        assertNull(guard.accept(frame));
        assertEquals(MossFrameDegenerationGuard.Reason.IDENTICAL_FRAMES, guard.accept(frame));
    }

    @Test
    void differentFrameBreaksTheIdenticalRun() {
        MossFrameDegenerationGuard guard = new MossFrameDegenerationGuard(3, 5, PAD);
        guard.accept(List.of(10, 20, 30));
        guard.accept(List.of(10, 20, 30));

        assertNull(guard.accept(List.of(11, 20, 30)));
        assertNull(guard.accept(List.of(11, 20, 30)));
    }

    @Test
    void fullyPaddedFramesEndTheUtteranceEvenWhenTheyDiffer() {
        MossFrameDegenerationGuard guard = new MossFrameDegenerationGuard(8, 3, PAD);

        assertNull(guard.accept(List.of(PAD, PAD, PAD)));
        assertNull(guard.accept(List.of(PAD, PAD, PAD)));
        assertEquals(MossFrameDegenerationGuard.Reason.PADDED_FRAMES, guard.accept(List.of(PAD, PAD, PAD)));
    }

    @Test
    void oneCarryingCodeIsNotAPaddedFrame() {
        MossFrameDegenerationGuard guard = new MossFrameDegenerationGuard(8, 2, PAD);

        assertNull(guard.accept(List.of(PAD, PAD, 7)));
        assertNull(guard.accept(List.of(PAD, PAD, 8)));
    }

    @Test
    void repeatedSyllablesInTheTextDoNotLookLikeDegeneration() {
        // A repeated word is still speech: the frames differ from step to step, so neither rule fires.
        MossFrameDegenerationGuard guard = new MossFrameDegenerationGuard(8, 5, PAD);
        for (int step = 0; step < 60; step++) {
            assertNull(guard.accept(List.of(700 + step, 300 + step % 7, 41 + step)));
        }
    }

    @Test
    void zeroLimitsDisableTheMatchingRule() {
        MossFrameDegenerationGuard guard = new MossFrameDegenerationGuard(0, 0, PAD);
        for (int step = 0; step < 20; step++) {
            assertNull(guard.accept(List.of(PAD, PAD, PAD)));
        }
    }
}
