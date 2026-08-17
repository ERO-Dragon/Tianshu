package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import com.rheinmetal.tianshu.function.tts.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.function.tts.runtime.TtsFailureException;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** MOSS-only recovery for a complete synthesis result that has not been handed off. */
final class MossOfflineSynthesisRetry {
    private final LongSupplier seedSource;

    MossOfflineSynthesisRetry(LongSupplier seedSource) {
        this.seedSource = Objects.requireNonNull(seedSource, "seedSource");
    }

    <T> T execute(BooleanSupplier cancellationRequested, SeededAttempt<T> attempt) throws Exception {
        BooleanSupplier cancellation = cancellationRequested == null ? () -> false : cancellationRequested;
        long firstSeed = seedSource.getAsLong();
        try {
            return attempt.run(firstSeed);
        } catch (MossRepeatedFrameException firstFailure) {
            if (cancellation.getAsBoolean()) {
                throw firstFailure;
            }
            try {
                return attempt.run(differentSeed(firstSeed));
            } catch (MossRepeatedFrameException secondFailure) {
                throw new TtsFailureException(TtsFailureCode.GENERATION_REPEATED, secondFailure.getMessage());
            }
        }
    }

    private long differentSeed(long previousSeed) {
        long nextSeed = seedSource.getAsLong();
        return nextSeed == previousSeed ? previousSeed + 1L : nextSeed;
    }

    @FunctionalInterface
    interface SeededAttempt<T> {
        T run(long seed) throws Exception;
    }
}
