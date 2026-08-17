package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.rheinmetal.tianshu.function.tts.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.function.tts.runtime.TtsFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MossOfflineSynthesisRetryTest {
    @Test
    void retriesRepeatedFrameFailureOnceWithDifferentSeed() throws Exception {
        MossOfflineSynthesisRetry retry = new MossOfflineSynthesisRetry(() -> 41L);
        AtomicInteger attempts = new AtomicInteger();
        List<Long> seeds = new ArrayList<>();

        String result = retry.execute(() -> false, seed -> {
            seeds.add(seed);
            if (attempts.getAndIncrement() == 0) {
                throw new MossRepeatedFrameException(8, 10, 17);
            }
            return "audio";
        });

        assertEquals("audio", result);
        assertEquals(2, attempts.get());
        assertNotEquals(seeds.get(0), seeds.get(1));
    }

    @Test
    void doesNotRetryUnrelatedFailure() {
        MossOfflineSynthesisRetry retry = new MossOfflineSynthesisRetry(() -> 41L);
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> retry.execute(() -> false, seed -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("codec failed");
        }));

        assertEquals(1, attempts.get());
    }

    @Test
    void secondRepeatedFrameFailureEscapes() {
        MossOfflineSynthesisRetry retry = new MossOfflineSynthesisRetry(() -> 41L);
        AtomicInteger attempts = new AtomicInteger();

        TtsFailureException failure = assertThrows(TtsFailureException.class, () -> retry.execute(() -> false, seed -> {
            attempts.incrementAndGet();
            throw new MossRepeatedFrameException(8, 10, 17);
        }));

        assertEquals(2, attempts.get());
        assertEquals(TtsFailureCode.GENERATION_REPEATED, failure.code());
    }
}
