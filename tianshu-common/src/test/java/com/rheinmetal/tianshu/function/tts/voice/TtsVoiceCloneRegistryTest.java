package com.rheinmetal.tianshu.function.tts.voice;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.function.tts.runtime.TtsControlAction;
import com.rheinmetal.tianshu.function.tts.runtime.TtsControlResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsVoiceCloneRegistryTest {
    @TempDir
    Path tempDir;

    @Test
    void loadCachesReferenceAudioFromVoiceLibrary() throws Exception {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        Path voiceDir = config.getVoiceLibraryPath();
        Files.createDirectories(voiceDir);
        writeSineWave(voiceDir.resolve("sample.wav"), 16_000, 160);
        TtsVoiceCloneRegistry registry = new TtsVoiceCloneRegistry(new FakeGameEnvironment(), config);

        TtsControlResult result = registry.load("maid", "module.maid", "sample.wav", "hello");

        assertTrue(result.accepted());
        assertEquals(TtsControlAction.LOAD_VOICE, result.action());
        TtsVoiceCloneProfile profile = registry.resolve("maid").orElseThrow();
        assertEquals("hello", profile.referenceText());
        assertEquals(16_000, profile.referenceAudio().sampleRate());
        assertTrue(profile.referenceAudio().samples().length > 0);
    }

    @Test
    void loadRejectsSampleOutsideVoiceLibrary() throws Exception {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        Path external = tempDir.resolve("external.wav");
        writeSineWave(external, 16_000, 160);
        TtsVoiceCloneRegistry registry = new TtsVoiceCloneRegistry(new FakeGameEnvironment(), config);

        TtsControlResult result = registry.load("maid", "module.maid", external.toString(), "hello");

        assertFalse(result.accepted());
        assertTrue(registry.resolve("maid").isEmpty());
    }

    @Test
    void importVoiceStoresUnderOwnerDirectoryAndLoadsProfile() throws Exception {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        TtsVoiceCloneRegistry registry = new TtsVoiceCloneRegistry(new FakeGameEnvironment(), config);
        byte[] audio = sineWaveBytes(16_000, 160);

        TtsControlResult result = registry.importVoice(
                "create:wrench",
                "create",
                audio,
                "wrench ready"
        );

        assertTrue(result.accepted());
        assertEquals(TtsControlAction.IMPORT_VOICE, result.action());
        Path ownerDir = config.getVoiceLibraryPath().resolve("create");
        assertTrue(Files.isRegularFile(ownerDir.resolve("create_wrench.wav")));
        TtsVoiceCloneProfile profile = registry.resolve("create:wrench").orElseThrow();
        assertEquals("wrench ready", profile.referenceText());
        assertEquals(ownerDir.resolve("create_wrench.wav").normalize(), profile.samplePath());
    }

    @Test
    void importVoiceRejectsOversizedAudio() {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        TtsVoiceCloneRegistry registry = new TtsVoiceCloneRegistry(new FakeGameEnvironment(), config);
        byte[] audio = new byte[TtsVoiceCloneRegistry.maxImportedAudioBytes() + 1];

        TtsControlResult result = registry.importVoice(
                "maid",
                "module.maid",
                audio,
                "hello"
        );

        assertFalse(result.accepted());
        assertTrue(registry.resolve("maid").isEmpty());
    }

    private static void writeSineWave(Path path, int sampleRate, int frames) throws Exception {
        byte[] pcm = sinePcm(frames);
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16, 1, 2, sampleRate, false);
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(stream, javax.sound.sampled.AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    private static byte[] sineWaveBytes(int sampleRate, int frames) throws Exception {
        byte[] pcm = sinePcm(frames);
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16, 1, 2, sampleRate, false);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(stream, javax.sound.sampled.AudioFileFormat.Type.WAVE, output);
        }
        return output.toByteArray();
    }

    private static byte[] sinePcm(int frames) {
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            short sample = (short) (Math.sin(i / 8.0D) * 12000.0D);
            pcm[i * 2] = (byte) (sample & 0xFF);
            pcm[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return pcm;
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
