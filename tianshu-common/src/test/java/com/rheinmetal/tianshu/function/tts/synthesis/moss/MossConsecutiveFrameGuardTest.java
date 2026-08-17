package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MossConsecutiveFrameGuardTest {
    @Test
    void repeatedCompleteFramesFailOnlyAfterConfiguredRun() {
        MossConsecutiveFrameGuard guard = new MossConsecutiveFrameGuard(3);
        List<Integer> frame = List.of(10, 20, 30);

        guard.accept(frame, 0);
        guard.accept(frame, 1);
        assertThrows(MossRepeatedFrameException.class, () -> guard.accept(frame, 2));
    }

    @Test
    void differentFrameBreaksTheRun() {
        MossConsecutiveFrameGuard guard = new MossConsecutiveFrameGuard(3);
        guard.accept(List.of(10, 20, 30), 0);
        guard.accept(List.of(10, 20, 30), 1);
        guard.accept(List.of(11, 20, 30), 2);

        assertDoesNotThrow(() -> guard.accept(List.of(10, 20, 30), 3));
    }
}
