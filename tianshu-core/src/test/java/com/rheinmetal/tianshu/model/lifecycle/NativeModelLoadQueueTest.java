package com.rheinmetal.tianshu.model.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate's contract, stated as the failure modes it exists to prevent.
 *
 * <p>Every case here corresponds to a way the native-load lane could deadlock or overlap: a deferred
 * load that jumps ahead of retrieval, a permit released twice (which would hand another module's permit
 * away), a permit held by a module that threw before it could release, and a permit accumulated across
 * repeated request/release cycles.
 */
class NativeModelLoadQueueTest {
    private final NativeModelLoadQueue queue = new NativeModelLoadQueue();
    private final List<String> ran = new ArrayList<>();

    private Runnable submit(String name) {
        return () -> ran.add(name);
    }

    @Test
    void deferredWaitsForThePrimaryAndIsThenGrantedInArrivalOrder() {
        queue.claimPrimary();
        assertEquals(NativeModelLoadQueue.Outcome.PARKED,
                queue.request("module.tts", "tts-model", submit("tts"), false));
        assertEquals(NativeModelLoadQueue.Outcome.PARKED,
                queue.request("module.asr", "asr-model", submit("asr"), false));
        assertEquals(List.of(), ran, "a deferred load must not start while retrieval is still expected");

        assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                queue.request("module.llm", "embedding", submit("llm"), true));
        assertEquals(List.of("llm"), ran);

        queue.release("module.llm");
        assertEquals(List.of("llm", "tts"), ran, "the first deferred request is granted when retrieval ends");

        queue.release("module.tts");
        assertEquals(List.of("llm", "tts", "asr"), ran);
    }

    @Test
    void refusesInsteadOfGrowingWithoutBound() {
        queue.claimPrimary();
        int parked = 0;
        while (parked < 1_000
                && queue.request("module.asr", "asr-model", () -> { }, false) == NativeModelLoadQueue.Outcome.PARKED) {
            parked++;
        }

        assertEquals(NativeModelLoadQueue.Outcome.REFUSED,
                queue.request("module.asr", "asr-model", submit("asr"), false),
                "a refused request must be reported, not silently dropped");
        assertEquals(List.of(), ran, "a refused request must never run");
        assertTrue(parked > 0, "the refusal is reached only after the parked tier is full");
    }

    @Test
    void releaseFromAnotherModuleDoesNotStealThePermit() {
        assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                queue.request("module.asr", "asr-model", submit("asr"), false));

        queue.release("module.tts");

        assertEquals(NativeModelLoadQueue.Outcome.PARKED,
                queue.request("module.tts", "tts-model", submit("tts"), false),
                "a foreign release must not free a permit its holder is still using");
        queue.release("module.asr");
        assertEquals(List.of("asr", "tts"), ran);
    }

    @Test
    void aSubmitThatThrowsHandsThePermitBack() {
        assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                queue.request("module.asr", "asr-model", () -> {
                    throw new IllegalStateException("the lane rejected the task");
                }, false));

        assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                queue.request("module.tts", "tts-model", submit("tts"), false),
                "a lane that refuses the submission must not leave the gate taken");
        assertEquals(List.of("tts"), ran);
    }

    @Test
    void aDuplicateReleaseLeavesTheNewHolderAlone() {
        queue.request("module.asr", "asr-model", submit("asr"), false);
        queue.request("module.tts", "tts-model", submit("tts"), false);
        queue.release("module.asr");
        assertEquals(List.of("asr", "tts"), ran);

        queue.release("module.asr");

        assertEquals(NativeModelLoadQueue.Outcome.PARKED,
                queue.request("module.llm", "embedding", submit("llm"), false),
                "the second release must not have handed the permit away");
    }

    @Test
    void abandoningThePrimaryLetsTheDeferredLoadsThrough() {
        queue.claimPrimary();
        assertEquals(NativeModelLoadQueue.Outcome.PARKED,
                queue.request("module.asr", "asr-model", submit("asr"), false));
        assertEquals(List.of(), ran);

        queue.abandonPrimary();

        assertEquals(List.of("asr"), ran, "a retrieval claim that will never load must not park speech");
    }

    @Test
    void repeatedRequestReleaseCyclesDoNotAccumulatePermits() {
        for (int cycle = 0; cycle < 50; cycle++) {
            assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                    queue.request("module.asr", "asr-model", submit("asr"), false),
                    "cycle " + cycle + " must still be granted: a leaked permit would park it");
            queue.release("module.asr");
        }

        assertEquals(50, ran.size());
        assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                queue.request("module.tts", "tts-model", submit("tts"), false));
    }

    /**
     * The initial read and a hotword-triggered reload are two loads of the same module, and this is the
     * discipline they rely on: the second waits for the first to hand the permit back, and the release that
     * ends the first is the only one that counts.
     */
    @Test
    void aSecondLoadOfTheSameModuleWaitsForThePermitAndIsReleasedOnce() {
        assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                queue.request("module.asr", "asr-model", submit("initial"), false));
        assertEquals(NativeModelLoadQueue.Outcome.PARKED,
                queue.request("module.asr", "asr-model", submit("hotword-reload"), false),
                "a reload must not start on top of a read that is still running");
        assertEquals(List.of("initial"), ran);

        queue.release("module.asr");
        assertEquals(List.of("initial", "hotword-reload"), ran);

        queue.release("module.asr");
        assertEquals(NativeModelLoadQueue.Outcome.GRANTED,
                queue.request("module.tts", "tts-model", submit("tts"), false),
                "the reload's own release must leave the gate free, and the duplicate must not disturb it");
    }
}
