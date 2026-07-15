package com.rheinmetal.tianshu.client.settings;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsRefreshCoalescingTest {
    @Test
    void modelDownloadPagesCoalesceRefreshRequests() throws Exception {
        assertCoalesced("asr/AsrSettingsRegistrySource.java");
        assertCoalesced("llm/LlmSettingsRegistrySource.java");
        assertCoalesced("tts/TtsSettingsRegistrySource.java");
    }

    private static void assertCoalesced(String relativePath) throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/client/settings/module").resolve(relativePath),
                StandardCharsets.UTF_8
        );
        assertTrue(source.contains("downloadRefreshQueued.compareAndSet(false, true)"), relativePath);
        assertTrue(source.contains("downloadRefreshQueued.set(false)"), relativePath);
    }
}
