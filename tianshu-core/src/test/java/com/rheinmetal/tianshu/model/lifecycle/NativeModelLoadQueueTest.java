package com.rheinmetal.tianshu.model.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate's contract, stated as the failure modes it exists to prevent.
 *
 * <p>Every case here corresponds to a way the native-load lane could deadlock, overlap, or deliver the
 * capabilities in the wrong order: a worse tier overtaking a better one, a permit released twice (which
 * would hand another module's permit away), a permit held by a module that threw before it could release,
 * and a permit accumulated across repeated request/release cycles.
 */
class NativeModelLoadQueueTest {
    private static final String LLM = "module.llm";
    private static final String TTS = "module.tts";
    private static final String ASR = "module.asr";

    private final NativeModelLoadQueue queue = new NativeModelLoadQueue();
    private final List<String> ran = new ArrayList<>();

    private Runnable submit(String name) {
        return () -> ran.add(name);
    }

    /** The speech-output tier already loaded, so the gate is free for the voice-input tier. */
    private void speechOutputSettled() {
        queue.settle(NativeModelLoadQueue.Tier.DEFERRED);
    }

    @Test
    void retrievalRunsFirstThenSpeechOutputThenSpeechInput() {
        queue.claimPrimary();
        assertEquals(NativeModelLoadQueue.Outcome.PARKED,
                queue.request(TTS, "tts-model", submit("tts"), NativeModelLoadQueue.Tier.DEFERRED));
        assertEquals(NativeModelLoadQueue.Outcome.PARKED,
                queue.request(ASR, "asr-model", submit("asr"), NativeModelLoadQueue.Tier.LAST));
        assertEquals(List.of(), ran, "nothing may start while retrieval is still expected");

        assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                queue.request(LLM, "embedding", submit("llm"), NativeModelLoadQueue.Tier.PRIMARY));
        assertEquals(List.of("llm"), ran);

        queue.release(LLM);
        assertEquals(List.of("llm", "tts"), ran, "speech output is granted when retrieval ends");

        queue.release(TTS);
        assertEquals(List.of("llm", "tts", "asr"), ran, "speech input is granted last");
    }

    /**
     * The order is the tiers' order, not the arrival order: the voice-input module is the one that asks
     * first in practice, and it still may not read its model before the speech-output one.
     */
    @Test
    void voiceInputNeverOvertakesSpeechOutputEvenWhenItAsksFirst() {
        assertEquals(NativeModelLoadQueue.Outcome.PARKED,
                queue.request(ASR, "asr-model", submit("asr"), NativeModelLoadQueue.Tier.LAST),
                "the last tier must wait for the speech-output tier it knows is still to come");
        assertEquals(List.of(), ran);

        assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                queue.request(TTS, "tts-model", submit("tts"), NativeModelLoadQueue.Tier.DEFERRED));
        assertEquals(List.of("tts"), ran);

        queue.release(TTS);
        assertEquals(List.of("tts", "asr"), ran);
    }

    @Test
    void voiceInputProceedsWhenSpeechOutputGivesUp() {
        assertEquals(NativeModelLoadQueue.Outcome.PARKED,
                queue.request(ASR, "asr-model", submit("asr"), NativeModelLoadQueue.Tier.LAST));

        queue.abandon(NativeModelLoadQueue.Tier.DEFERRED);

        assertEquals(List.of("asr"), ran, "a speech-output tier that will never load must not park input");
    }

    @Test
    void refusesInsteadOfGrowingWithoutBound() {
        queue.claimPrimary();
        int parked = 0;
        while (parked < 1_000
                && queue.request(ASR, "asr-model", () -> { }, NativeModelLoadQueue.Tier.LAST)
                == NativeModelLoadQueue.Outcome.PARKED) {
            parked++;
        }

        assertEquals(NativeModelLoadQueue.Outcome.REFUSED,
                queue.request(ASR, "asr-model", submit("asr"), NativeModelLoadQueue.Tier.LAST),
                "a refused request must be reported, not silently dropped");
        assertEquals(List.of(), ran, "a refused request must never run");
        assertTrue(parked > 0, "the refusal is reached only after the parked tier is full");
    }

    @Test
    void releaseFromAnotherModuleDoesNotStealThePermit() {
        speechOutputSettled();
        assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                queue.request(ASR, "asr-model", submit("asr"), NativeModelLoadQueue.Tier.LAST));

        queue.release(TTS);

        assertEquals(NativeModelLoadQueue.Outcome.PARKED,
                queue.request(TTS, "tts-model", submit("tts"), NativeModelLoadQueue.Tier.DEFERRED),
                "a foreign release must not free a permit its holder is still using");
        queue.release(ASR);
        assertEquals(List.of("asr", "tts"), ran);
    }

    @Test
    void aSubmitThatThrowsHandsThePermitBack() {
        speechOutputSettled();
        assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                queue.request(ASR, "asr-model", () -> {
                    throw new IllegalStateException("the lane rejected the task");
                }, NativeModelLoadQueue.Tier.LAST));

        assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                queue.request(TTS, "tts-model", submit("tts"), NativeModelLoadQueue.Tier.DEFERRED),
                "a lane that refuses the submission must not leave the gate taken");
        assertEquals(List.of("tts"), ran);
    }

    @Test
    void aDuplicateReleaseLeavesTheNewHolderAlone() {
        speechOutputSettled();
        queue.request(ASR, "asr-model", submit("asr"), NativeModelLoadQueue.Tier.LAST);
        queue.request(TTS, "tts-model", submit("tts"), NativeModelLoadQueue.Tier.DEFERRED);
        queue.release(ASR);
        assertEquals(List.of("asr", "tts"), ran);

        queue.release(ASR);

        assertEquals(NativeModelLoadQueue.Outcome.PARKED,
                queue.request(LLM, "embedding", submit("llm"), NativeModelLoadQueue.Tier.PRIMARY),
                "the second release must not have handed the permit away");
    }

    @Test
    void abandoningTheRetrievalClaimLetsTheSpeechLoadsThrough() {
        queue.claimPrimary();
        assertEquals(NativeModelLoadQueue.Outcome.PARKED,
                queue.request(ASR, "asr-model", submit("asr"), NativeModelLoadQueue.Tier.LAST));
        assertEquals(List.of(), ran);

        queue.abandonPrimary();

        assertEquals(List.of(), ran, "speech output is still expected, so input must keep waiting");

        queue.abandon(NativeModelLoadQueue.Tier.DEFERRED);
        assertEquals(List.of("asr"), ran);
    }

    @Test
    void repeatedRequestReleaseCyclesDoNotAccumulatePermits() {
        speechOutputSettled();
        for (int cycle = 0; cycle < 50; cycle++) {
            assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                    queue.request(ASR, "asr-model", submit("asr"), NativeModelLoadQueue.Tier.LAST),
                    "cycle " + cycle + " must still be granted: a leaked permit would park it");
            queue.release(ASR);
        }

        assertEquals(50, ran.size());
        assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                queue.request(TTS, "tts-model", submit("tts"), NativeModelLoadQueue.Tier.DEFERRED));
    }

    /**
     * The initial read and a hotword-triggered reload are two loads of the same module: the second waits
     * for the first to hand the permit back, and the release that ends the first is the only one that counts.
     */
    @Test
    void aSecondLoadOfTheSameModuleWaitsForThePermitAndIsReleasedOnce() {
        speechOutputSettled();
        assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                queue.request(ASR, "asr-model", submit("initial"), NativeModelLoadQueue.Tier.LAST));
        assertEquals(NativeModelLoadQueue.Outcome.PARKED,
                queue.request(ASR, "asr-model", submit("hotword-reload"), NativeModelLoadQueue.Tier.LAST),
                "a reload must not start on top of a read that is still running");
        assertEquals(List.of("initial"), ran);

        queue.release(ASR);
        assertEquals(List.of("initial", "hotword-reload"), ran);

        queue.release(ASR);
        assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                queue.request(TTS, "tts-model", submit("tts"), NativeModelLoadQueue.Tier.DEFERRED),
                "the reload's own release must leave the gate free, and the duplicate must not disturb it");
    }
}
