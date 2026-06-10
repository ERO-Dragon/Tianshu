package com.rheinmetal.tianshu.protocol.voice;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceResourceManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void materializesSharedRegistryAndSkipsUnchangedHotwords() throws Exception {
        VoiceResourceManager manager = manager();
        AtomicInteger notifications = new AtomicInteger();
        manager.addChangeListener(ignored -> notifications.incrementAndGet());
        manager.voiceTriggers().register(new VoiceTriggerRegistration("module.maid", List.of("酒狐"), List.of("farm")));

        VoiceResourceSnapshot first = manager.materialize();
        VoiceResourceSnapshot second = manager.materialize();

        assertEquals(first.version(), second.version());
        assertEquals(1, notifications.get());
        assertFalse(first.hotwordFingerprint().isBlank());
        assertEquals(List.of("酒狐"), Files.readAllLines(first.zhHotwordsFile()));
        assertEquals(List.of("farm"), Files.readAllLines(first.enHotwordsFile()));
    }

    @Test
    void registryChangeListenerCanDriveMaterialization() {
        VoiceResourceManager manager = manager();
        AtomicInteger notifications = new AtomicInteger();
        manager.addChangeListener(ignored -> notifications.incrementAndGet());
        manager.voiceTriggers().addChangeListener(manager::materialize);

        manager.voiceTriggers().register(new VoiceTriggerRegistration("module.maid", List.of("酒狐"), List.of()));
        long firstVersion = manager.snapshot().version();
        manager.voiceTriggers().register(new VoiceTriggerRegistration("module.maid", List.of("酒狐"), List.of()));
        manager.voiceTriggers().register(new VoiceTriggerRegistration("module.create", List.of("机械动力"), List.of()));

        assertTrue(firstVersion > 0L);
        assertEquals(firstVersion + 1L, manager.snapshot().version());
        assertEquals(2, notifications.get());
    }

    private VoiceResourceManager manager() {
        return new VoiceResourceManager(new FakeGameEnvironment(), new TestLlmSupport.FakeConfig(tempDir));
    }

    private static final class FakeGameEnvironment implements IGameEnvironment {
        @Override public void displayMessageToPlayer(String message) {}
        @Override public void executeOnMainThread(Runnable task) { task.run(); }
        @Override public Path getGameDirectory() { return Path.of("."); }
        @Override public boolean isClientSide() { return true; }
        @Override public void openFolder(Path dir) {}
        @Override public void info(String msg) {}
        @Override public void warn(String msg) {}
        @Override public void error(String msg, Throwable t) {}
    }
}
