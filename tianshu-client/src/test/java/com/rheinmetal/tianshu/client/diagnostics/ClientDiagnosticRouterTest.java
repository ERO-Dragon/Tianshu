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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void enabledDiagnosticCanBeForwardedAsProductProgressMessage() throws Exception {
        Path root = Files.createTempDirectory("tianshu-diagnostics-chat");
        java.util.List<ClientDiagnosticMessage> messages = new java.util.ArrayList<>();
        ClientDiagnosticRouter router = new ClientDiagnosticRouter(root, ignored -> true, 16, 8L * 1024L * 1024L, 5, messages::add);
        router.publish(DiagnosticEvent.now("module.ax", "LLM_SUBMITTED", DiagnosticSeverity.INFO,
                DiagnosticPrivacy.RAW_CONTENT, Map.of("text", "raw-content")));
        router.close();

        assertEquals(1, messages.size());
        assertEquals("tianshu.chat.progress.thinking", messages.get(0).translationKey());
        assertTrue(messages.get(0).arguments().isEmpty());
    }

    @Test
    void chatSummaryUsesProductStageWithoutDiagnosticIdentifiers() throws Exception {
        Path root = Files.createTempDirectory("tianshu-diagnostics-chat-stage");
        java.util.List<ClientDiagnosticMessage> messages = new java.util.ArrayList<>();
        ClientDiagnosticRouter router = new ClientDiagnosticRouter(root, ignored -> true, 16,
                8L * 1024L * 1024L, 5, messages::add);
        router.publish(DiagnosticEvent.now("module.asr", "STREAM_RESULT", DiagnosticSeverity.INFO,
                DiagnosticPrivacy.RAW_CONTENT,
                Map.of("sessionId", "99", "turnId", "4", "inputMode", "always", "text", "hello")));
        router.close();

        assertEquals(1, messages.size());
        assertEquals("tianshu.chat.progress.asr.recognition", messages.get(0).translationKey());
        assertEquals(java.util.List.of("hello"), messages.get(0).arguments());
    }

    @Test
    void chatSummaryKeepsLlmInputAndOutputWithoutInternalIdentifiers() throws Exception {
        Path root = Files.createTempDirectory("tianshu-diagnostics-chat-content");
        java.util.List<ClientDiagnosticMessage> messages = new java.util.ArrayList<>();
        ClientDiagnosticRouter router = new ClientDiagnosticRouter(root, ignored -> true, 16,
                8L * 1024L * 1024L, 5, messages::add);
        router.publish(DiagnosticEvent.now("module.llm", "CHAT_COMPLETED", DiagnosticSeverity.INFO,
                DiagnosticPrivacy.RAW_CONTENT,
                Map.of("requestId", "request-1", "input", "player input", "output", "assistant output")));
        router.close();

        assertEquals(1, messages.size());
        assertEquals("tianshu.chat.progress.llm.reply", messages.get(0).translationKey());
        assertEquals(java.util.List.of("player input", "assistant output"), messages.get(0).arguments());
    }

    @Test
    void ordinaryRuntimeLogIsWrittenWithoutDebugChatSummary() throws Exception {
        Path root = Files.createTempDirectory("tianshu-runtime-log");
        java.util.List<ClientDiagnosticMessage> messages = new java.util.ArrayList<>();
        ClientDiagnosticRouter router = new ClientDiagnosticRouter(root, ignored -> true, 16,
                8L * 1024L * 1024L, 5, messages::add);
        router.info("runtime.started");
        router.warn("runtime.warning");
        router.error("runtime.failed", new IllegalStateException("broken"));
        router.close();

        String content = Files.readString(root.resolve("logs/tianshu-diagnostics.log"), StandardCharsets.UTF_8);
        assertTrue(content.contains("runtime.started"));
        assertTrue(content.contains("runtime.warning"));
        assertTrue(content.contains("runtime.failed"));
        assertTrue(messages.isEmpty());
    }

    @Test
    void clientDiagnosticImplementationDoesNotDependOnHostLogger() throws Exception {
        Path sourceRoot = Path.of("src/main/java/com/rheinmetal/tianshu/client/diagnostics");
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                assertFalse(source.contains("System.Logger"), () -> "Host logger dependency in " + file);
                assertFalse(source.contains("org.slf4j"), () -> "Host logger dependency in " + file);
            }
        }
    }

    private static DiagnosticEvent event(String moduleId, String code) {
        return DiagnosticEvent.now(moduleId, code, DiagnosticSeverity.ERROR, DiagnosticPrivacy.RAW_CONTENT,
                Map.of("text", "raw-content"));
    }
}
