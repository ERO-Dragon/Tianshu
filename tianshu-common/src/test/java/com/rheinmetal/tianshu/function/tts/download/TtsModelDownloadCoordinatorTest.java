package com.rheinmetal.tianshu.function.tts.download;

import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsModelDownloadCoordinatorTest {
    @Test
    void pausedWaiterIsReleasedByResumeWithoutPollingSleep() throws Exception {
        TtsModelDownloadCoordinator.DownloadSession session = new TtsModelDownloadCoordinator(
                new TestLlmSupport.FakeGameEnvironment()).newSession();
        session.pause();
        CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> await(session));

        Thread.sleep(50L);
        assertFalse(waiting.isDone());
        session.resume();

        waiting.get(1, TimeUnit.SECONDS);
        String source = Files.readString(sourcePath(), StandardCharsets.UTF_8);
        assertFalse(source.contains("Thread.sleep"));
    }

    @Test
    void cancelWakesPausedWaiterWithCancelledFailure() throws Exception {
        TtsModelDownloadCoordinator.DownloadSession session = new TtsModelDownloadCoordinator(
                new TestLlmSupport.FakeGameEnvironment()).newSession();
        session.pause();
        CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> await(session));

        Thread.sleep(50L);
        session.cancel();

        ExecutionException exception = assertThrows(ExecutionException.class, () -> waiting.get(1, TimeUnit.SECONDS));
        assertTrue(exception.getCause() instanceof DownloadAwaitException);
        assertTrue(exception.getCause().getCause() instanceof TtsModelDownloadCoordinator.DownloadCancelledException);
    }

    private static void await(TtsModelDownloadCoordinator.DownloadSession session) {
        try {
            session.awaitReady();
        } catch (Exception exception) {
            throw new DownloadAwaitException(exception);
        }
    }

    private static Path sourcePath() {
        return Path.of("src/main/java/com/rheinmetal/tianshu/function/tts/download/TtsModelDownloadCoordinator.java");
    }

    private static final class DownloadAwaitException extends RuntimeException {
        private DownloadAwaitException(Throwable cause) {
            super(cause);
        }
    }
}
