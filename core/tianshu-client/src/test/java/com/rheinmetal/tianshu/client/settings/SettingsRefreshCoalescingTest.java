package com.rheinmetal.tianshu.client.settings;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsRefreshCoalescingTest {
    @Test
    void modelDownloadPagesCoalesceRefreshRequests() throws Exception {
        assertCoalesced("intake/IntakeSettingsRegistrySource.java");
        assertCoalesced("weaving/WeavingSettingsRegistrySource.java");
        assertCoalesced("echo/EchoSettingsRegistrySource.java");
    }

    private static void assertCoalesced(String relativePath) throws Exception {
        String source = Files.readString(
                SettingsSourcePaths.page(relativePath),
                StandardCharsets.UTF_8
        );
        assertTrue(source.contains("downloadRefreshQueued.compareAndSet(false, true)"), relativePath);
        assertTrue(source.contains("downloadRefreshQueued.set(false)"), relativePath);
    }
}
