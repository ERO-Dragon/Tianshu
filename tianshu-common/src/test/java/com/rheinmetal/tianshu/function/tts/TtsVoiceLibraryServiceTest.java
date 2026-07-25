package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TtsVoiceLibraryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void refreshAndImportUpdateTheCachedVoiceSampleList() throws Exception {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        Path voiceDirectory = config.getVoiceLibraryPath();
        Files.createDirectories(voiceDirectory);
        Files.writeString(voiceDirectory.resolve("existing.wav"), "audio");
        Path importSource = tempDir.resolve("new.wav");
        Files.writeString(importSource, "new audio");

        try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            TtsVoiceLibraryService service = new TtsVoiceLibraryService(
                    new TestLlmSupport.FakeGameEnvironment(),
                    config,
                    executors
            );

            CountDownLatch refreshed = new CountDownLatch(1);
            service.refreshVoiceSamplesAsync(refreshed::countDown);
            assertTrue(refreshed.await(5, TimeUnit.SECONDS));
            assertEquals(java.util.List.of("existing.wav"), service.voiceSamples());

            AtomicReference<String> imported = new AtomicReference<>();
            CountDownLatch importCompleted = new CountDownLatch(1);
            service.importVoiceSampleAsync(importSource, value -> {
                imported.set(value);
                importCompleted.countDown();
            });

            assertTrue(importCompleted.await(5, TimeUnit.SECONDS));
            assertEquals("new.wav", imported.get());
            assertEquals(java.util.List.of("existing.wav", "new.wav"), service.voiceSamples());
            assertTrue(Files.isRegularFile(voiceDirectory.resolve("new.wav")));
        }
    }
}
