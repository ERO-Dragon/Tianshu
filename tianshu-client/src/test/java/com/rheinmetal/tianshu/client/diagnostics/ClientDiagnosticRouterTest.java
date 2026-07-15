package com.rheinmetal.tianshu.client.diagnostics;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientDiagnosticRouterTest {
    @Test
    void disabledModuleDoesNotCreateDiagnosticFile() throws Exception {
        Path root = Files.createTempDirectory("tianshu-diagnostics-disabled");
        ClientDiagnosticRouter router = new ClientDiagnosticRouter(root, ignored -> false);
        router.publish(event("module.asr", "DISABLED"));
        router.close();

        assertFalse(Files.exists(root.resolve("logs/tianshu-diagnostics.log")));
    }

    @Test
    void enabledModuleWritesStructuredEventOffTheCallerThread() throws Exception {
        Path root = Files.createTempDirectory("tianshu-diagnostics-enabled");
        ClientDiagnosticRouter router = new ClientDiagnosticRouter(root, "module.asr"::equals);
        router.publish(event("module.asr", "RECOGNITION_FAILED"));
        router.close();

        Path file = root.resolve("logs/tianshu-diagnostics.log");
        assertTrue(Files.exists(file));
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.contains("RECOGNITION_FAILED"));
        assertTrue(content.contains("module.asr"));
    }

    @Test
    void closeIsIdempotent() throws Exception {
        Path root = Files.createTempDirectory("tianshu-diagnostics-close");
        ClientDiagnosticRouter router = new ClientDiagnosticRouter(root, ignored -> true);
        router.publish(event("module.tts", "SYNTHESIS_FAILED"));
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            router.close();
            router.close();
        });
    }

    @Test
    void aNewClientLifetimeCanCreateAFreshRouterAfterPreviousClose() throws Exception {
        Path root = Files.createTempDirectory("tianshu-diagnostics-reentry");
        ClientDiagnosticRouter first = new ClientDiagnosticRouter(root, ignored -> true);
        first.publish(event("module.ia", "FIRST_WORLD"));
        first.close();

        ClientDiagnosticRouter second = new ClientDiagnosticRouter(root, ignored -> true);
        second.publish(event("module.ia", "SECOND_WORLD"));
        second.close();

        String content = Files.readString(root.resolve("logs/tianshu-diagnostics.log"), StandardCharsets.UTF_8);
        assertTrue(content.contains("FIRST_WORLD"));
        assertTrue(content.contains("SECOND_WORLD"));
    }

    @Test
    void boundedQueueDropsBurstEventsWithoutBlockingPublisher() throws Exception {
        Path root = Files.createTempDirectory("tianshu-diagnostics-burst");
        ClientDiagnosticRouter router = new ClientDiagnosticRouter(root, ignored -> true, 1, 8L * 1024L * 1024L, 1);

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int index = 0; index < 10_000; index++) {
                router.publish(event("module.llm", "BURST_" + index));
            }
        });
        router.close();

        assertTrue(router.droppedEventCount() > 0L);
    }

    @Test
    void rotatesDiagnosticFileAtConfiguredLimit() throws Exception {
        Path root = Files.createTempDirectory("tianshu-diagnostics-rotation");
        ClientDiagnosticRouter router = new ClientDiagnosticRouter(root, ignored -> true, 128, 256L, 2);
        for (int index = 0; index < 32; index++) {
            router.publish(event("module.tts", "ROTATION_" + index));
        }
        router.close();

        Path logFile = root.resolve("logs/tianshu-diagnostics.log");
        assertTrue(Files.exists(logFile));
        assertTrue(Files.exists(logFile.resolveSibling("tianshu-diagnostics.log.1")));
    }

    private static DiagnosticEvent event(String moduleId, String code) {
        return DiagnosticEvent.now(moduleId, code, DiagnosticSeverity.ERROR, DiagnosticPrivacy.RAW_CONTENT,
                Map.of("text", "raw-content"));
    }
}
