package com.rheinmetal.tianshu.function.echo.synthesis.moss;

import java.util.List;

/**
 * Detects the two ways the MOSS autoregressive loop stops saying anything without stopping.
 *
 * <p>It either locks onto one complete audio frame, or it keeps emitting the audio codec's padding
 * code for every channel. Both leave the frames "valid" but silent, so the loop only ends at the frame
 * cap — for a short input that is tens of seconds of a fading tail, and the streaming path has already
 * played it by then. Neither pattern appears in healthy speech: a real pause is encoded, not padded.</p>
 */
final class MossFrameDegenerationGuard {

    enum Reason {
        /** One complete frame repeats: the model locked onto a single audio step. */
        IDENTICAL_FRAMES,
        /** Every channel carries the codec padding code: nothing is being said any more. */
        PADDED_FRAMES
    }

    private final int maximumIdenticalFrames;
    private final int maximumPaddedFrames;
    private final int audioPadTokenId;

    private List<Integer> previousFrame;
    private int identicalFrameRun;
    private int paddedFrameRun;

    MossFrameDegenerationGuard(int maximumIdenticalFrames, int maximumPaddedFrames, int audioPadTokenId) {
        if (maximumIdenticalFrames < 0 || maximumPaddedFrames < 0) {
            throw new IllegalArgumentException("MOSS degeneration limits cannot be negative");
        }
        this.maximumIdenticalFrames = maximumIdenticalFrames;
        this.maximumPaddedFrames = maximumPaddedFrames;
        this.audioPadTokenId = audioPadTokenId;
    }

    /**
     * @return why this frame ends the utterance, or {@code null} while the generated frames are healthy
     */
    Reason accept(List<Integer> frame) {
        if (frame == null || frame.isEmpty()) {
            throw new IllegalArgumentException("MOSS audio-code frame cannot be empty");
        }
        identicalFrameRun = frame.equals(previousFrame) ? identicalFrameRun + 1 : 1;
        previousFrame = List.copyOf(frame);
        paddedFrameRun = isFullyPadded(frame) ? paddedFrameRun + 1 : 0;

        if (maximumIdenticalFrames > 0 && identicalFrameRun >= maximumIdenticalFrames) {
            return Reason.IDENTICAL_FRAMES;
        }
        if (maximumPaddedFrames > 0 && paddedFrameRun >= maximumPaddedFrames) {
            return Reason.PADDED_FRAMES;
        }
        return null;
    }

    int identicalFrameRun() {
        return identicalFrameRun;
    }

    int paddedFrameRun() {
        return paddedFrameRun;
    }

    private boolean isFullyPadded(List<Integer> frame) {
        for (Integer code : frame) {
            if (code == null || code != audioPadTokenId) {
                return false;
            }
        }
        return true;
    }
}
