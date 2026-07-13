package com.rheinmetal.tianshu.function.llm.download;

import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.model.LlmModelDownloader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmModelDownloadCoordinatorCleanBoundaryTest {
    @Test
    void pauseGateDoesNotPollWithThreadSleep() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/function/llm/download/LlmModelDownloadCoordinator.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(source.contains("Thread.sleep"));
    }

    @Test
    void resumeWakesPausedDownloadWithoutPolling() {
        LlmModelDownloadCoordinator.DownloadSession session = newSession();
        session.pause();
        CompletableFuture<Void> waiting = awaitReadyAsync(session);

        assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));
        session.resume();

        assertDoesNotThrow(() -> waiting.get(1, TimeUnit.SECONDS));
    }

    @Test
    void cancelWakesPausedDownloadWithCancellation() {
        LlmModelDownloadCoordinator.DownloadSession session = newSession();
        session.pause();
        CompletableFuture<Void> waiting = awaitReadyAsync(session);

        assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));
        session.cancel();

        CompletionException failure = assertThrows(CompletionException.class, waiting::join);
        assertInstanceOf(LlmModelDownloader.DownloadCancelledException.class, failure.getCause());
    }

    private static LlmModelDownloadCoordinator.DownloadSession newSession() {
        return new LlmModelDownloadCoordinator(new TestLlmSupport.FakeGameEnvironment()).newSession();
    }

    private static CompletableFuture<Void> awaitReadyAsync(LlmModelDownloadCoordinator.DownloadSession session) {
        return CompletableFuture.runAsync(() -> {
            try {
                session.awaitReady();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }
}
