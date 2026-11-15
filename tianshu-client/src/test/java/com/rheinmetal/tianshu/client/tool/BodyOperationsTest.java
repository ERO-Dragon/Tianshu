package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.tool.CancellationSignal;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyOperationsTest {

    @AfterEach
    void clear() {
        BodyOperations.release("body-1");
    }

    @Test
    void aSecondLongOperationOnTheSameBodyIsBusy() {
        assertTrue(BodyOperations.acquire("body-1").isEmpty());
        ToolException refusal = BodyOperations.acquire("body-1").orElseThrow();
        assertEquals(ToolError.BUSY, refusal.code());
    }

    @Test
    void releasingLetsTheNextOperationStart() {
        assertTrue(BodyOperations.acquire("body-1").isEmpty());
        BodyOperations.release("body-1");
        assertTrue(BodyOperations.acquire("body-1").isEmpty());
    }

    @Test
    void guardedRunsOnceAndReleasesOnCompletion() {
        AtomicInteger runs = new AtomicInteger();
        CompletableFuture<String> result = BodyOperations.guarded("body-1", CancellationSignal.NEVER, () -> {
            runs.incrementAndGet();
            assertTrue(BodyOperations.acquire("body-1").isPresent(), "the slot is held while running");
            return CompletableFuture.completedFuture("done");
        });
        assertEquals("done", result.join());
        assertEquals(1, runs.get());
        assertTrue(BodyOperations.acquire("body-1").isEmpty(), "the slot was released");
    }

    @Test
    void theBoundSignalIsWhatAdaptersPoll() {
        AtomicBoolean cancelled = new AtomicBoolean();
        CancellationSignal signal = cancelled::get;
        BodyOperations.bindCancellation("body-1", signal);
        assertFalse(BodyOperations.cancelled("body-1"));
        cancelled.set(true);
        assertTrue(BodyOperations.cancelled("body-1"));
        BodyOperations.release("body-1");
        assertFalse(BodyOperations.cancelled("body-1"));
    }
}