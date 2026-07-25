package com.rheinmetal.tianshu.client.llm.performance;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GpuInfoTest {
    @Test
    void statusReadsNeverStartHardwareDetection() throws Exception {
        String source = read("src/main/java/com/rheinmetal/tianshu/client/llm/performance/GpuInfo.java");

        assertFalse(source.contains("REFRESH_INTERVAL_MILLIS"));
        assertFalse(methodBody(source, "public static List<GpuDevice> devices()").contains("requestRefresh"));
        assertFalse(methodBody(source, "public static boolean detecting()").contains("snapshot()"));
        assertFalse(methodBody(source, "public static boolean detecting()").contains("requestRefresh"));
        assertTrue(methodBody(source, "public static List<GpuDevice> devices()").contains("SNAPSHOT.get().devices()"));
    }

    @Test
    void llmSettingsKeepCompletedSnapshotVisibleDuringRefresh() throws Exception {
        String source = read("src/main/java/com/rheinmetal/tianshu/client/settings/module/llm/LlmSettingsRegistrySource.java");
        String provider = read("src/main/java/com/rheinmetal/tianshu/client/llm/performance/ClientLlmPerformanceProvider.java");

        assertFalse(source.contains("!GpuInfo.detected() || GpuInfo.detecting()"));
        assertFalse(source.contains("GpuInfo.detected() && !GpuInfo.detecting()"));
        assertTrue(source.contains("GpuInfo.requestRefresh("));
        assertTrue(methodBody(provider, "public LlmPerformanceSnapshot performanceSnapshot()").contains("GpuInfo.requestRefresh(null)"));
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new AssertionError("Missing method: " + signature);
        }
        int opening = source.indexOf('{', start);
        int depth = 0;
        for (int index = opening; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') depth++;
            if (value == '}' && --depth == 0) {
                return source.substring(opening + 1, index);
            }
        }
        throw new AssertionError("Unclosed method: " + signature);
    }
}
