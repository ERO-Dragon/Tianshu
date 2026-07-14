package com.rheinmetal.tianshu.function.asr.download;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.model.AsrModelDownloader;
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

class AsrModelDownloadCoordinatorTest {
    @Test
    void pauseGateDoesNotPollWithThreadSleep() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/function/asr/download/AsrModelDownloadCoordinator.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(source.contains("Thread.sleep"));
    }

    @Test
    void resumeWakesPausedDownload() {
        AsrModelDownloadCoordinator.DownloadSession session = newSession();
        session.pause();
        CompletableFuture<Void> waiting = awaitReadyAsync(session);

        assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));
        session.resume();

        assertDoesNotThrow(() -> waiting.get(1, TimeUnit.SECONDS));
    }

    @Test
    void cancelWakesPausedDownloadWithCancellation() {
        AsrModelDownloadCoordinator.DownloadSession session = newSession();
        session.pause();
        CompletableFuture<Void> waiting = awaitReadyAsync(session);

        assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));
        session.cancel();

        CompletionException failure = assertThrows(CompletionException.class, waiting::join);
        assertInstanceOf(AsrModelDownloader.DownloadCancelledException.class, failure.getCause());
    }

    private static AsrModelDownloadCoordinator.DownloadSession newSession() {
        return new AsrModelDownloadCoordinator(new FakeGameEnvironment()).newSession();
    }

    private static CompletableFuture<Void> awaitReadyAsync(AsrModelDownloadCoordinator.DownloadSession session) {
        return CompletableFuture.runAsync(() -> invokeAwaitReady(session));
    }

    private static void invokeAwaitReady(AsrModelDownloadCoordinator.DownloadSession session) {
        try {
            session.awaitReady();
        } catch (Exception exception) {
            throw new CompletionException(exception);
        }
    }

    private static final class FakeGameEnvironment implements IGameEnvironment {
        @Override
        public void displayMessageToPlayer(String message) {
        }

        @Override
        public void executeOnMainThread(Runnable task) {
            task.run();
        }

        @Override
        public Path getGameDirectory() {
            return Path.of(".");
        }

        @Override
        public boolean isClientSide() {
            return true;
        }

        @Override
        public void openFolder(Path dir) {
        }

        @Override
        public void info(String msg) {
        }

        @Override
        public void warn(String msg) {
        }

        @Override
        public void error(String msg, Throwable t) {
        }
    }
}
