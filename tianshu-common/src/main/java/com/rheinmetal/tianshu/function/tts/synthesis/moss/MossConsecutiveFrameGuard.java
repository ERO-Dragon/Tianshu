package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import java.util.List;

/** Detects the MOSS autoregressive failure mode that locks one complete audio frame. */
final class MossConsecutiveFrameGuard {
    private final int maximumConsecutiveIdenticalFrames;
    private List<Integer> previousFrame;
    private int consecutiveFrameCount;
    private int firstRepeatedFrameIndex;

    MossConsecutiveFrameGuard(int maximumConsecutiveIdenticalFrames) {
        if (maximumConsecutiveIdenticalFrames < 0) {
            throw new IllegalArgumentException("MOSS repeated-frame limit cannot be negative");
        }
        this.maximumConsecutiveIdenticalFrames = maximumConsecutiveIdenticalFrames;
    }

    void accept(List<Integer> frame, int generatedFrameIndex) {
        if (frame == null || frame.isEmpty()) {
            throw new IllegalArgumentException("MOSS audio-code frame cannot be empty");
        }
        if (frame.equals(previousFrame)) {
            consecutiveFrameCount++;
        } else {
            previousFrame = List.copyOf(frame);
            consecutiveFrameCount = 1;
            firstRepeatedFrameIndex = generatedFrameIndex;
        }
        if (maximumConsecutiveIdenticalFrames > 0
                && consecutiveFrameCount >= maximumConsecutiveIdenticalFrames) {
            throw new MossRepeatedFrameException(
                    consecutiveFrameCount,
                    firstRepeatedFrameIndex,
                    generatedFrameIndex
            );
        }
    }
}
