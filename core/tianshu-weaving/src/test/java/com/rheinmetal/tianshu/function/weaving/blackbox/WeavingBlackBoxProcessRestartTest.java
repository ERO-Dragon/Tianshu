package com.rheinmetal.tianshu.function.weaving.blackbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A true process-level restart: one JVM writes the RAG cache and exits, another JVM boots the module
 * on the same home and reads it back. The module restart test covers reloading in one JVM; this one
 * covers the file surviving process exit and a fresh runtime reading it.
 */
class WeavingBlackBoxProcessRestartTest {
    @TempDir
    Path tempDir;

    @Test
    void aFreshJvmReadsTheCacheWrittenByAnotherJvm() throws Exception {
        assertEquals(0, runProbe("write"), "the writing JVM must finish cleanly");
        assertEquals(0, runProbe("read"), "a fresh JVM must find the persisted cache");
    }

    private int runProbe(String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(
                java,
                "-cp", System.getProperty("java.class.path"),
                BlackBoxCacheProbe.class.getName(),
                tempDir.toAbsolutePath().toString(),
                mode
        ).redirectErrorStream(true).start();

        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        boolean exited = process.waitFor(60, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        assertTrue(exited, "probe \"" + mode + "\" did not exit: " + output);
        if (process.exitValue() != 0) {
            System.err.println("probe \"" + mode + "\" output:\n" + output);
        }
        return process.exitValue();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}