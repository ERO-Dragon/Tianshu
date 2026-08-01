package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsCodecExecution;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsProtocolCodecExecutionTest {
    @Test
    void cancellationWaitsForRunningCodecWorkToExit() throws Exception {
        try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            TtsProtocolCodecExecution execution = new TtsProtocolCodecExecution(executors);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch exited = new CountDownLatch(1);

            TtsCodecExecution.Task task = execution.submit(() -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exited.countDown();
                }
            });

            assertTrue(task.accepted());
            assertTrue(started.await(2L, TimeUnit.SECONDS));
            task.cancel("test cancellation");
            task.await(() -> false);
            assertTrue(exited.await(100L, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void closedProtocolRuntimeRejectsCodecWork() {
        ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run);
        executors.close();
        TtsCodecExecution.Task task = new TtsProtocolCodecExecution(executors).submit(() -> { });

        assertFalse(task.accepted());
    }
}
