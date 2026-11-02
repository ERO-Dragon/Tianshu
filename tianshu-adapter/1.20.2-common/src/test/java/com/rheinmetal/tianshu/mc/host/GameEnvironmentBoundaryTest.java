package com.rheinmetal.tianshu.mc.host;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code IGameEnvironment.logs()} defaults to a no-op sink, so a platform that binds a sink through
 * {@code bindLogs} but never overrides the accessor keeps working while silently swallowing every line a
 * module reports about itself. That failure is invisible in the game and invisible in the log, which is
 * exactly why it needs a source-level guard: the 2026-10-30 acceptance run found the assembly line missing
 * for this reason and nothing else pointed at it.
 */
final class GameEnvironmentBoundaryTest {

    @Test
    void theBoundLogSinkIsReachableThroughTheEnvironmentAccessor() throws Exception {
        String environment = Files.readString(
                Path.of("../1.21-1.21.1-common/src/main/java/com/rheinmetal/tianshu/mc/host/GameEnvironment.java"),
                StandardCharsets.UTF_8);

        assertTrue(environment.contains("public void bindLogs(LogSink logSink)"));
        assertTrue(environment.contains("public LogSink logs()"));
        assertTrue(methodBody(environment, "public LogSink logs()").contains("return logSink;"));
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
