package com.rheinmetal.tianshu.function.echo.synthesis.moss;

import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

record MossFrameGenerationResult(
        List<List<Integer>> frames,
        MossFrameGenerationTermination termination,
        int maxFrameCount
) {
    MossFrameGenerationResult {
        frames = immutableFrames(frames);
        termination = termination == null
                ? MossFrameGenerationTermination.FRAME_LIMIT_REACHED
                : termination;
        maxFrameCount = Math.max(0, maxFrameCount);
    }

    static MossFrameGenerationResult naturalEnd(List<List<Integer>> frames, int maxFrameCount) {
        return new MossFrameGenerationResult(frames, MossFrameGenerationTermination.NATURAL_END, maxFrameCount);
    }

    static MossFrameGenerationResult degenerateEnd(List<List<Integer>> frames, int maxFrameCount) {
        return new MossFrameGenerationResult(frames, MossFrameGenerationTermination.DEGENERATE_END, maxFrameCount);
    }

    static MossFrameGenerationResult cancelled(List<List<Integer>> frames, int maxFrameCount) {
        return new MossFrameGenerationResult(frames, MossFrameGenerationTermination.CANCELLED, maxFrameCount);
    }

    static MossFrameGenerationResult frameLimitReached(List<List<Integer>> frames, int maxFrameCount) {
        return new MossFrameGenerationResult(frames, MossFrameGenerationTermination.FRAME_LIMIT_REACHED, maxFrameCount);
    }

    boolean naturallyEnded() {
        return termination == MossFrameGenerationTermination.NATURAL_END;
    }

    /** The model produced usable speech but stopped on its own degeneration, so the utterance is short. */
    boolean degenerateEnded() {
        return termination == MossFrameGenerationTermination.DEGENERATE_END;
    }

    boolean cancelled() {
        return termination == MossFrameGenerationTermination.CANCELLED;
    }

    int generatedFrameCount() {
        return frames.size();
    }

    /**
     * Frame set of a generation that finished on its own terms. A truncated utterance still counts as
     * finished; only a run that hit the frame cap without ending is a failure.
     */
    List<List<Integer>> requireFinishedGeneration(int inputTokenCount) throws MossGenerationCancelledException {
        if (cancelled()) {
            throw new MossGenerationCancelledException();
        }
        if (termination == MossFrameGenerationTermination.FRAME_LIMIT_REACHED) {
            throw frameLimitFailure(inputTokenCount);
        }
        return frames;
    }

    TtsFailureException frameLimitFailure(int inputTokenCount) {
        return new TtsFailureException(
                TtsFailureCode.GENERATION_LIMIT_REACHED,
                "TTS_MOSS_GENERATION_LIMIT_REACHED inputTokens=" + Math.max(0, inputTokenCount)
                        + " generatedFrames=" + generatedFrameCount()
                        + " maxFrames=" + maxFrameCount
        );
    }

    private static List<List<Integer>> immutableFrames(List<List<Integer>> frames) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }
        List<List<Integer>> copy = new ArrayList<>(frames.size());
        for (List<Integer> frame : frames) {
            copy.add(Collections.unmodifiableList(new ArrayList<>(frame == null ? List.of() : frame)));
        }
        return Collections.unmodifiableList(copy);
    }
}
