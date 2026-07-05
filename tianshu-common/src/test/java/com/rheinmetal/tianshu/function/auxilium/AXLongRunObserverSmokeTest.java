package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextCollector;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXRuntimeLlmBudgetResolver;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXDynamicFactClient;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSystem;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptOrchestrator;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXDialogueInputMapper;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXInputNormalizer;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXKnowledgeHit;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXEventVector;
import com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance.AXMemoryFactExtractionParser;
import com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance.AXMemoryMaintenanceService;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.AXMemoryRetrievalRequest;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.AXMemoryRetrievalResult;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.AXMemoryRetrievalTrace;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.AXMemoryRetriever;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.index.AXMemoryRetrievalIndexSnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance.AXMemoryTaskPromptRepository;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputContext;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputMode;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputProcessor;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptResourceRepository;
import com.rheinmetal.tianshu.function.auxilium.core.maintenance.AXRuntimeMaintenanceCoordinator;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.function.ia.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.function.ia.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.function.llm.service.LLMRequest;
import com.rheinmetal.tianshu.function.llm.service.LLMService;
import com.rheinmetal.tianshu.function.llm.service.MessageItem;
import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.libs.llm.KvCacheType;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmClient;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPrimitiveClient;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmRagClient;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPromptRequestBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.turn.AXSessionController;
import com.rheinmetal.tianshu.function.auxilium.core.turn.AXTurnOrchestrator;
import com.rheinmetal.tianshu.function.auxilium.core.turn.AXTurnStatusPublisher;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXGameContextKnowledgePlanner;

@EnabledIfSystemProperty(named = "tianshu.ax.longrun.smoke", matches = "true")
class AXLongRunObserverSmokeTest {
    private static final String DEFAULT_MODEL_FILE = "Qwen3.5-2B-Q4_K_M.gguf";
    private static final String DEFAULT_EMBEDDING_MODEL_FILE = "bge-large-zh-v1.5-q4_k_m.gguf";
    private static final String CHAT_MODEL_NAME = "qwen3.5-2b-ax-longrun";
    private static final String EMBEDDING_MODEL_NAME = "bge-large-zh-v1.5";

    @TempDir
    Path tempDir;

    @Test
    void longRunningObserverGrowsDialogueAndMemoryNaturally() throws Exception {
        Path modelPath = resolveSmokeModelPath("tianshu.llm.smoke.model", DEFAULT_MODEL_FILE);
        Path embeddingModelPath = resolveSmokeModelPath("tianshu.llm.smoke.embeddingModel", DEFAULT_EMBEDDING_MODEL_FILE);
        assertTrue(Files.isRegularFile(modelPath), "LLM smoke model does not exist: " + modelPath);
        assertTrue(Files.isRegularFile(embeddingModelPath), "LLM embedding smoke model does not exist: " + embeddingModelPath);
        assertNotDeepSeek(modelPath);

        JavaLlamaServer server = JavaLlamaServer.builder()
                .model(modelPath.toString())
                .modelAlias(CHAT_MODEL_NAME)
                .modelProfile("auto")
                .contextSize(intProperty("tianshu.ax.longrun.context", 4096))
                .chatThreads(2)
                .chatMaxQueueSize(2)
                .taskThreads(1)
                .requestTimeoutSeconds(intProperty("tianshu.ax.longrun.timeoutSeconds", 180))
                .cacheTypeK(KvCacheType.Q8_0)
                .cacheTypeV(KvCacheType.Q8_0)
                .gpuLayers(intProperty("tianshu.ax.longrun.gpuLayers", 9999))
                .embeddingModel(embeddingModelPath.toString())
                .embeddingAlias(EMBEDDING_MODEL_NAME)
                .embeddingContextSize(512)
                .embeddingThreads(2)
                .embeddingGpuLayers(intProperty("tianshu.ax.longrun.embeddingGpuLayers", 9999))
                .build();

        try {
            server.start();
            LLMService service = LLMService.builder()
                    .env(new TestLlmSupport.FakeGameEnvironment())
                    .config(new TestLlmSupport.FakeConfig(tempDir.resolve("llm")).customLlmName(CHAT_MODEL_NAME))
                    .aiService(server)
                    .embeddingConfigured(true)
                    .usePersistentCache(false)
                    .build();

            SmokeHarness harness = SmokeHarness.create(tempDir, service);
            FormatSmokeRecord formatSmoke = runExtractionFormatSmoke(service, harness.taskPromptRepository());
            List<RoundRecord> records = new ArrayList<>();
            String question = "我在调试天枢 AX，一边看日志一边整理记忆。你先帮我判断现在应该关注什么？";
            int rounds = intProperty("tianshu.ax.longrun.rounds", 10);
            for (int i = 1; i <= rounds; i++) {
                RoundRecord record = harness.runRound(i, question);
                records.add(record);
                writeReport(modelPath, embeddingModelPath, records, harness.snapshotMemory(), harness.storageSnapshot(), formatSmoke, harness.promptRecorder().requests(), harness.promptRecorder().responses(), harness.logKnowledgeLines());
                question = generateNextQuestion(service, question, record.answer(), i);
            }

            AXMemorySnapshot finalMemory = harness.snapshotMemory();
            writeReport(modelPath, embeddingModelPath, records, finalMemory, harness.storageSnapshot(), formatSmoke, harness.promptRecorder().requests(), harness.promptRecorder().responses(), harness.logKnowledgeLines());

            assertFalse(records.isEmpty());
            assertTrue(records.stream().allMatch(record -> !record.answer().isBlank()), "AX answer should not be blank");
            assertTrue(
                    harness.recentDialogueSize() + finalMemory.recentPlayerMemoryBlocks().size() >= 1,
                    "dialogue or STM memory should grow over the long run"
            );
        } finally {
            server.shutdown();
        }
    }

    private static String generateNextQuestion(LLMService service, String previousQuestion, String previousAnswer, int round) {
        LLMRequest request = LLMRequest.ofMessage(
                MessageItem.system("""
                        你是一个测试脚本里的下一问生成器。基于上一轮用户问题和 AX 回复，生成下一轮玩家会问 AX 的一句中文问题。
                        只输出问题本身，不要解释，不要编号。问题要自然延续调试、记忆、日志、游戏上下文观察这条线。
                        """),
                MessageItem.user("上一问：" + previousQuestion + "\nAX 回复：" + previousAnswer)
        );
        request.setMaxTokens(0);
        request.setThinking(false);
        request.setLane("TASK");
        request.setTaskPriority(100);
        request.setTaskPreemptible(true);
        String text;
        try {
            text = service.submitTask(request).get(180, TimeUnit.SECONDS);
        } catch (Exception e) {
            text = "";
        }
        String normalized = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').strip();
        if (normalized.isBlank()) {
            return "继续第 " + (round + 1) + " 轮观察：刚才这段信息会怎样影响你的记忆和下一步判断？";
        }
        int questionMark = Math.max(normalized.lastIndexOf('？'), normalized.lastIndexOf('?'));
        if (questionMark >= 0) {
            normalized = normalized.substring(0, questionMark + 1);
        }
        return normalized.length() > 180 ? normalized.substring(0, 180) : normalized;
    }

    private static FormatSmokeRecord runExtractionFormatSmoke(LLMService service, AXMemoryTaskPromptRepository promptRepository) {
        String stm = "\u73a9\u5bb6\u8fde\u7eed\u8c03\u8bd5 AX \u957f\u671f\u8fd0\u884c\u89c2\u5bdf\u5668\uff0c\u8981\u6c42\u65e5\u5fd7 RAG \u4ee5\u4e00\u884c\u4e00\u6761\u7684\u5f62\u5f0f\u8fdb\u5165 game_context\u3002\n"
                + "AX \u5df2\u7ecf\u628a\u538b\u7f29\u548c E \u62bd\u53d6\u6539\u4e3a TASK lane\uff0cmaxTokens \u4e3a 0\uff0c\u5e76\u5f00\u542f thinking \u4f46\u4e0d\u63a5\u6536\u601d\u8003\u5185\u5bb9\u3002\n"
                + "\u73a9\u5bb6\u62c5\u5fc3 0.6B \u5c0f\u6a21\u578b\u5728\u957f\u80cc\u666f\u4e0b\u4e0d\u80fd\u7a33\u5b9a\u8f93\u51fa JSON \u6570\u7ec4\u3002";
        LLMRequest request = LLMRequest.ofMessage(
                MessageItem.system(promptRepository.extractionSystemPrompt()),
                MessageItem.user(promptRepository.extractionUserPrompt(stm))
        );
        request.setMaxTokens(0);
        request.setThinking(true);
        request.setLane("TASK");
        request.setTaskPriority(50);
        request.setTaskPreemptible(true);
        try {
            String raw = service.submitTask(request).get(180, TimeUnit.SECONDS);
            String cleaned = raw == null ? "" : raw.strip();
            List<String> facts = new AXMemoryFactExtractionParser().parse(cleaned);
            return new FormatSmokeRecord(true, raw, cleaned, facts, "");
        } catch (Exception e) {
            return new FormatSmokeRecord(false, "", "", List.of(), e.getMessage());
        }
    }

    private static Path resolveSmokeModelPath(String propertyName, String defaultFileName) {
        String configured = System.getProperty(propertyName, defaultFileName);
        if (configured == null || configured.isBlank()) {
            configured = defaultFileName;
        }
        Path input = Path.of(configured);
        if (input.isAbsolute()) {
            return input.normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path inCwd = cwd.resolve(input).normalize();
        if (Files.isRegularFile(inCwd)) {
            return inCwd;
        }
        Path inParent = cwd.getParent() == null ? inCwd : cwd.getParent().resolve(input).normalize();
        if (Files.isRegularFile(inParent)) {
            return inParent;
        }
        return inCwd;
    }

    private static void assertNotDeepSeek(Path modelPath) {
        String name = modelPath == null ? "" : modelPath.getFileName().toString().toLowerCase(Locale.ROOT);
        assertFalse(name.contains("deepseek"), "AX long-run smoke must not use the DeepSeek distilled 9B model: " + modelPath);
    }

    private static int intProperty(String name, int fallback) {
        try {
            return Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void writeReport(
            Path modelPath,
            Path embeddingModelPath,
            List<RoundRecord> records,
            AXMemorySnapshot finalMemory,
            MemoryStoreSnapshot storageSnapshot,
            FormatSmokeRecord formatSmoke,
            List<LLMPromptRequestPayload> prompts,
            List<PromptResponseRecord> responses,
            List<String> logKnowledgeLines
    ) throws Exception {
        Path reportPath = Path.of("build", "reports", "ax", "long-run-observer-smoke.md");
        Files.createDirectories(reportPath.getParent());
        StringBuilder report = new StringBuilder();
        report.append("# AX Long Run Observer Smoke\n\n");
        report.append("- generatedAt: `").append(Instant.now()).append("`\n");
        report.append("- chatModel: `").append(modelPath.toAbsolutePath().normalize()).append("`\n");
        report.append("- embeddingModel: `").append(embeddingModelPath.toAbsolutePath().normalize()).append("`\n");
        report.append("- rounds: `").append(records.size()).append("`\n");
        report.append("- finalRawTurns: `").append(records.isEmpty() ? 0 : records.get(records.size() - 1).rawTurns()).append("`\n");
        report.append("- finalRetrievedStm: `").append(finalMemory.retrievedPlayerMemoryBlocks().size()).append("`\n");
        report.append("- finalRecentStm: `").append(finalMemory.recentPlayerMemoryBlocks().size()).append("`\n\n");
        if (storageSnapshot != null) {
            report.append("- storageStm: ").append(storageSnapshot.stmBlocks().size()).append('\n');
            report.append("- storageEvents: ").append(storageSnapshot.events().size()).append('\n');
            report.append("- storageVectors: ").append(storageSnapshot.vectorCount()).append('\n');
            report.append("- retrievalIndexSnapshots: ").append(storageSnapshot.indexSnapshots().size()).append('\n');
            report.append('\n');
        }
        report.append("## Fake Log RAG Source\n\n");
        report.append("- sourceLines: `").append(logKnowledgeLines == null ? 0 : logKnowledgeLines.size()).append("`\n");
        if (logKnowledgeLines != null) {
            logKnowledgeLines.stream().limit(24).forEach(line ->
                    report.append("- `").append(line.replace("`", "'")).append("`\n"));
        }
        report.append('\n');

        report.append("## Rounds\n\n");
        Map<String, AXMemoryEvent> eventsById = storageSnapshot == null ? Map.of() : storageSnapshot.events().stream()
                .collect(java.util.stream.Collectors.toMap(
                        AXMemoryEvent::id,
                        event -> event,
                        (first, second) -> first
                ));
        for (RoundRecord record : records) {
            report.append("### Round ").append(record.round()).append("\n\n");
            report.append("- rawTurns: `").append(record.rawTurns()).append("`\n");
            report.append("- retrievedStm: `").append(record.retrievedStm()).append("`\n");
            report.append("- recentStm: `").append(record.recentStm()).append("`\n\n");
            report.append("**Question**\n\n```text\n").append(record.question()).append("\n```\n\n");
            report.append("**Answer**\n\n```text\n").append(record.answer()).append("\n```\n\n");
            appendRetrievalTrace(report, record.retrievalTraces(), eventsById);
        }

        List<LLMPromptRequestPayload> chatPrompts = prompts.stream()
                .filter(payload -> payload != null && "CHAT".equalsIgnoreCase(payload.lane()))
                .toList();
        long taskPromptCount = prompts.stream()
                .filter(payload -> payload != null && "TASK".equalsIgnoreCase(payload.lane()))
                .count();
        report.append("## Prompt Snapshots\n\n");
        report.append("- capturedChatPrompts: `").append(chatPrompts.size()).append("`\n");
        report.append("- capturedTaskPrompts: `").append(taskPromptCount).append("`\n\n");
        appendPromptSnapshot(report, "first chat", chatPrompts, 0);
        appendPromptSnapshot(report, "middle chat", chatPrompts, chatPrompts.size() / 2);
        appendPromptSnapshot(report, "last chat", chatPrompts, chatPrompts.size() - 1);
        appendTaskPromptSummary(report, prompts);
        appendFormatSmoke(report, formatSmoke);
        appendTaskResponseSummary(report, responses);
        appendMemoryStoreSnapshot(report, storageSnapshot);
        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);
    }

    private static void appendTaskPromptSummary(StringBuilder report, List<LLMPromptRequestPayload> prompts) {
        List<LLMPromptRequestPayload> taskPrompts = prompts.stream()
                .filter(payload -> payload != null && "TASK".equalsIgnoreCase(payload.lane()))
                .toList();
        if (taskPrompts.isEmpty()) {
            return;
        }
        report.append("## Task Prompt Summary\n\n");
        for (LLMPromptRequestPayload payload : taskPrompts) {
            report.append("- `").append(payload.requestId()).append("`: maxTokens=`")
                    .append(payload.maxTokens()).append("`, thinking=`")
                    .append(payload.thinking()).append("`, captureThinkingContent=`")
                    .append(payload.captureThinkingContent()).append("`, priority=`")
                    .append(payload.taskPriority()).append("`\n");
        }
        report.append('\n');
    }

    private static void appendFormatSmoke(StringBuilder report, FormatSmokeRecord formatSmoke) {
        if (formatSmoke == null) {
            return;
        }
        report.append("## Extraction Format Smoke\n\n");
        report.append("- completed: ").append(formatSmoke.completed()).append('\n');
        report.append("- parsedFacts: ").append(formatSmoke.facts().size()).append('\n');
        report.append("- error: ").append(formatSmoke.error().isBlank() ? "none" : formatSmoke.error()).append("\n\n");
        report.append("### Raw Response\n\n~~~text\n")
                .append(preview(formatSmoke.rawText(), 2200))
                .append("\n~~~\n\n");
        report.append("### Cleaned Response\n\n~~~text\n")
                .append(preview(formatSmoke.cleanedText(), 2200))
                .append("\n~~~\n\n");
        report.append("### Parsed Facts\n\n");
        for (String fact : formatSmoke.facts()) {
            report.append("- ").append(fact).append('\n');
        }
        report.append('\n');
    }

    private static void appendTaskResponseSummary(StringBuilder report, List<PromptResponseRecord> responses) {
        List<PromptResponseRecord> taskResponses = responses == null ? List.of() : responses.stream()
                .filter(response -> response != null && "TASK".equalsIgnoreCase(response.lane()))
                .toList();
        if (taskResponses.isEmpty()) {
            return;
        }
        report.append("## Task Response Summary\n\n");
        for (PromptResponseRecord response : taskResponses) {
            report.append("### ").append(response.requestId()).append("\n\n");
            report.append("- lane: ").append(response.lane()).append('\n');
            report.append("- completed: ").append(response.completed()).append('\n');
            report.append("- error: ").append(response.error().isBlank() ? "none" : response.error()).append("\n\n");
            report.append("~~~text\n").append(preview(response.text(), 2400)).append("\n~~~\n\n");
        }
    }

    private static void appendRetrievalTrace(
            StringBuilder report,
            List<AXMemoryRetrievalTrace> traces,
            Map<String, AXMemoryEvent> eventsById
    ) {
        if (traces == null || traces.isEmpty()) {
            return;
        }
        report.append("**E -> STM Retrieval Trace**\n\n");
        for (AXMemoryRetrievalTrace trace : traces) {
            report.append("- stmId: `").append(trace.stmId()).append("`, score: `")
                    .append(String.format(Locale.ROOT, "%.4f", trace.score()))
                    .append("`\n");
            for (AXMemoryRetrievalTrace.EventHit hit : trace.eventHits().stream().limit(8).toList()) {
                AXMemoryEvent event = eventsById == null ? null : eventsById.get(hit.eventId());
                report.append("  - eventId: `").append(hit.eventId())
                        .append("`, mapping: `").append(hit.effectiveMappingId())
                        .append("`, relevance: `").append(String.format(Locale.ROOT, "%.4f", hit.relevance()))
                        .append("`");
                if (event != null && !event.fact().isBlank()) {
                    report.append(" ")
                            .append(preview(event.fact(), 180).replace('\n', ' '));
                }
                report.append('\n');
            }
        }
        report.append('\n');
    }

    private static void appendMemoryStoreSnapshot(StringBuilder report, MemoryStoreSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        report.append("## Memory Store Snapshot\n\n");
        report.append("- stmBlocks: ").append(snapshot.stmBlocks().size()).append('\n');
        report.append("- events: ").append(snapshot.events().size()).append('\n');
        report.append("- vectors: ").append(snapshot.vectorCount()).append("\n\n");

        report.append("### STM Blocks\n\n");
        for (AXStmBlock block : snapshot.stmBlocks().stream().limit(20).toList()) {
            report.append("- id: ").append(block.id())
                    .append(", turns: ").append(block.sourceTurnCount())
                    .append(", sourceToMillis: ").append(block.sourceToMillis())
                    .append('\n');
            report.append("  ").append(preview(block.content(), 420)).append("\n");
            if (!block.attachedEventIds().isEmpty()) {
                report.append("  attachedEventIds: ").append(block.attachedEventIds()).append('\n');
            }
        }
        report.append('\n');

        report.append("### Events\n\n");
        for (AXMemoryEvent event : snapshot.events().stream().limit(40).toList()) {
            report.append("- id: ").append(event.id())
                    .append(", stmId: ").append(event.stmId())
                    .append(", sourceKind: ").append(event.sourceKind())
                    .append(", happenedAtMillis: ").append(event.happenedAtMillis())
                    .append('\n');
            report.append("  ").append(preview(event.fact(), 360)).append("\n");
        }
        report.append('\n');

        report.append("### Vector Records\n\n");
        for (AXEventVector vector : snapshot.vectors().stream().limit(24).toList()) {
            report.append("- eventId: ").append(vector.eventId())
                    .append(", namespace: ").append(vector.embeddingNamespace())
                    .append(", dimension: ").append(vector.dimension())
                    .append('\n');
        }
        report.append('\n');

        report.append("### Retrieval Index Snapshots\n\n");
        for (AXMemoryRetrievalIndexSnapshot indexSnapshot : snapshot.indexSnapshots()) {
            report.append("- namespace: ").append(indexSnapshot.embeddingNamespace())
                    .append(", l1: ").append(indexSnapshot.l1Clusters().size())
                    .append(", l2: ").append(indexSnapshot.l2EffectiveMappings().size())
                    .append(", mappings: ").append(indexSnapshot.effectiveMappingByEventId().size())
                    .append(", eventsSize: ").append(indexSnapshot.eventsSize())
                    .append(", vectorsSize: ").append(indexSnapshot.vectorsSize())
                    .append('\n');
        }
        report.append('\n');
    }

    private static String preview(String text, int maxChars) {
        String normalized = text == null ? "" : text.replace('\r', ' ').strip();
        int limit = Math.max(0, maxChars);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private static void appendPromptSnapshot(StringBuilder report, String label, List<LLMPromptRequestPayload> prompts, int index) {
        if (prompts.isEmpty() || index < 0 || index >= prompts.size()) {
            return;
        }
        LLMPromptRequestPayload payload = prompts.get(index);
        report.append("### ").append(label).append("\n\n");
        report.append("- requestId: `").append(payload.requestId()).append("`\n");
        report.append("- maxTokens: `").append(payload.maxTokens()).append("`\n");
        report.append("- thinking: `").append(payload.thinking()).append("`\n\n");
        String text = payload.chunks().stream()
                .flatMap(chunk -> chunk.messageContent().stream())
                .map(message -> message.role() + ":\n" + message.content())
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
        report.append("```text\n").append(text).append("\n```\n\n");
    }

    private record RoundRecord(
            int round,
            String question,
            String answer,
            int rawTurns,
            int retrievedStm,
            int recentStm,
            List<AXMemoryRetrievalTrace> retrievalTraces
    ) {
        private RoundRecord {
            retrievalTraces = retrievalTraces == null ? List.of() : List.copyOf(retrievalTraces);
        }
    }

    private record MemoryStoreSnapshot(
            List<AXStmBlock> stmBlocks,
            List<AXMemoryEvent> events,
            List<AXEventVector> vectors,
            List<AXMemoryRetrievalIndexSnapshot> indexSnapshots
    ) {
        private MemoryStoreSnapshot {
            stmBlocks = stmBlocks == null ? List.of() : List.copyOf(stmBlocks);
            events = events == null ? List.of() : List.copyOf(events);
            vectors = vectors == null ? List.of() : List.copyOf(vectors);
            indexSnapshots = indexSnapshots == null ? List.of() : List.copyOf(indexSnapshots);
        }

        private int vectorCount() {
            return vectors.size();
        }
    }

    private record FormatSmokeRecord(
            boolean completed,
            String rawText,
            String cleanedText,
            List<String> facts,
            String error
    ) {
        private FormatSmokeRecord {
            rawText = rawText == null ? "" : rawText.trim();
            cleanedText = cleanedText == null ? "" : cleanedText.trim();
            facts = facts == null ? List.of() : List.copyOf(facts);
            error = error == null ? "" : error.trim();
        }
    }

    private record PromptResponseRecord(
            String requestId,
            String lane,
            boolean completed,
            String text,
            String error
    ) {
        private PromptResponseRecord {
            requestId = requestId == null ? "" : requestId.trim();
            lane = lane == null ? "" : lane.trim();
            text = text == null ? "" : text.trim();
            error = error == null ? "" : error.trim();
        }
    }

    private static List<String> loadLogKnowledgeLines() {
        List<String> lines = new ArrayList<>();
        for (Path file : candidateLogFiles()) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try (var stream = Files.lines(file, StandardCharsets.UTF_8)) {
                stream.map(line -> line == null ? "" : line.trim())
                        .filter(line -> !line.isBlank())
                        .filter(AXLongRunObserverSmokeTest::isUsefulLogLine)
                        .map(line -> file.getFileName() + " | " + line)
                        .forEach(lines::add);
            } catch (Exception ignored) {
            }
        }
        if (lines.isEmpty()) {
            lines.add("latest.log | [Render thread/INFO] [com.rheinmetal.tianshu.client.ir.ClientNamedObjectIndexManager/]: IR named object index loaded from cache");
            lines.add("debug.log | [Render thread/INFO] [com.rheinmetal.tianshu.function.auxilium/]: AX memory maintenance can compact raw turns into STM");
            lines.add("latest.log | [Render thread/INFO] [com.rheinmetal.tianshu.client.presence/]: Presence runtime facts are requested by capability response");
        }
        return lines.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(120)
                .toList();
    }

    private static List<Path> candidateLogFiles() {
        Path root = workspaceRoot();
        return List.of(
                root.resolve("tianshu-neoforge/run/logs/latest.log"),
                root.resolve("tianshu-neoforge/run/logs/debug.log")
        );
    }

    private static Path workspaceRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("tianshu-neoforge/run/logs"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("tianshu-neoforge/run/logs"))) {
            return parent;
        }
        return current;
    }

    private static boolean isUsefulLogLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String normalized = line.toLowerCase(Locale.ROOT);
        return normalized.contains("tianshu")
                || normalized.contains("cache")
                || normalized.contains("reload")
                || normalized.contains("resource")
                || normalized.contains("module")
                || normalized.contains("llm")
                || normalized.contains("rag")
                || normalized.contains("memory")
                || normalized.contains("prompt")
                || normalized.contains("minecraft")
                || normalized.contains("world")
                || normalized.contains("native");
    }

    private static final class LogLineKnowledgePlanner implements AXGameContextKnowledgePlanner {
        private final List<String> lines;

        private LogLineKnowledgePlanner(List<String> lines) {
            this.lines = lines == null ? List.of() : List.copyOf(lines);
        }

        @Override
        public List<AXKnowledgeHit> plan(AXRequest request, com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot context, AXContextBudget budget) {
            Set<String> terms = queryTerms(request == null ? "" : request.userText());
            List<String> matches = lines.stream()
                    .filter(line -> matches(line, terms))
                    .limit(Math.max(1, Math.min(8, budget == null ? 8 : budget.maxStaticContentItems())))
                    .toList();
            if (matches.isEmpty()) {
                matches = lines.stream().limit(4).toList();
            }
            return matches.isEmpty() ? List.of() : List.of(AXKnowledgeHit.of("ax.fake_log_knowledge.lines", matches));
        }

        private boolean matches(String line, Set<String> terms) {
            String normalized = line == null ? "" : line.toLowerCase(Locale.ROOT);
            for (String term : terms) {
                if (normalized.contains(term)) {
                    return true;
                }
            }
            return false;
        }

        private Set<String> queryTerms(String query) {
            String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
            Set<String> terms = new LinkedHashSet<>();
            addTerm(terms, normalized, "日志", "log");
            addTerm(terms, normalized, "资源", "resource");
            addTerm(terms, normalized, "重载", "reload");
            addTerm(terms, normalized, "缓存", "cache");
            addTerm(terms, normalized, "记忆", "memory");
            addTerm(terms, normalized, "提示词", "prompt");
            addTerm(terms, normalized, "上下文", "context");
            addTerm(terms, normalized, "rag", "rag");
            addTerm(terms, normalized, "llm", "llm");
            addTerm(terms, normalized, "ax", "ax");
            addTerm(terms, normalized, "天枢", "tianshu");
            addTerm(terms, normalized, "minecraft", "minecraft");
            return terms.isEmpty() ? Set.of("tianshu", "ax", "minecraft") : Set.copyOf(terms);
        }

        private void addTerm(Set<String> terms, String query, String trigger, String term) {
            if (query.contains(trigger)) {
                terms.add(term);
            }
        }
    }

    private static final class SmokeHarness {
        private final ProtocolRuntime runtime;
        private final AXTurnOrchestrator orchestrator;
        private final AXMemorySystem memorySystem;
        private final AXRecentDialogueSystem recentDialogueSystem;
        private final AXScope scope;
        private final RecordingChatSink chatSink;
        private final PromptRecorder promptRecorder;
        private final AXMemoryTaskPromptRepository taskPromptRepository;
        private final AXMemoryRetriever memoryRetriever;
        private final List<String> logKnowledgeLines;

        private SmokeHarness(
                ProtocolRuntime runtime,
                AXTurnOrchestrator orchestrator,
                AXMemorySystem memorySystem,
                AXRecentDialogueSystem recentDialogueSystem,
                AXScope scope,
                RecordingChatSink chatSink,
                PromptRecorder promptRecorder,
                AXMemoryTaskPromptRepository taskPromptRepository,
                AXMemoryRetriever memoryRetriever,
                List<String> logKnowledgeLines
        ) {
            this.runtime = runtime;
            this.orchestrator = orchestrator;
            this.memorySystem = memorySystem;
            this.recentDialogueSystem = recentDialogueSystem;
            this.scope = scope;
            this.chatSink = chatSink;
            this.promptRecorder = promptRecorder;
            this.taskPromptRepository = taskPromptRepository;
            this.memoryRetriever = memoryRetriever;
            this.logKnowledgeLines = logKnowledgeLines == null ? List.of() : List.copyOf(logKnowledgeLines);
        }

        static SmokeHarness create(Path tempDir, LLMService service) {
            ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run);
            AXProtocolAdapter axAdapter = new AXProtocolAdapter(runtime);
            AXScope scope = new AXScope("longrun-player", "longrun-world", "Long Run World", AXScopeKind.LOCAL_WORLD, true);
            TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
            AXStorageLayout layout = new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("ax")));
            AXJsonStore jsonStore = new AXJsonStore(env);
            AXMemoryWindowPolicy windowPolicy = new AXMemoryWindowPolicy(
                    8000,
                    2000,
                    2000,
                    1500,
                    1000,
                    1000,
                    25,
                    40,
                    25,
                    40,
                    28000,
                    120000,
                    3,
                    0L
            );
            AXMemorySystem memorySystem = new AXMemorySystem(layout, jsonStore, windowPolicy);
            AXRecentDialogueSystem recentDialogueSystem = new AXRecentDialogueSystem(windowPolicy);
            AXLlmClient llmClient = new AXLlmClient(axAdapter);
            AXLlmPrimitiveClient primitiveClient = new AXLlmPrimitiveClient(axAdapter, 120_000L);
            AXLlmRagClient ragClient = new AXLlmRagClient(axAdapter, 120_000L);
            AXPromptLanguageProvider languageProvider = AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN);
            AXPromptResourceRepository promptRepository = new AXPromptResourceRepository(layout, jsonStore);
            AXMemoryTaskPromptRepository taskPromptRepository = new AXMemoryTaskPromptRepository(layout, languageProvider);
            AXMemoryMaintenanceService memoryMaintenanceService = new AXMemoryMaintenanceService(
                    axAdapter,
                    memorySystem,
                    recentDialogueSystem,
                    llmClient,
                    primitiveClient,
                    ragClient,
                    taskPromptRepository
            );
            AXRuntimeMaintenanceCoordinator maintenanceCoordinator = new AXRuntimeMaintenanceCoordinator(memoryMaintenanceService);
            RecordingChatSink chatSink = new RecordingChatSink();
            PromptRecorder promptRecorder = new PromptRecorder(service);
            registerLlmCapability(runtime, promptRecorder);
            registerPrimitiveCapability(runtime, service);
            registerPresenceContextCapability(runtime);
            List<String> logKnowledgeLines = loadLogKnowledgeLines();
            AXPromptOrchestrator promptOrchestrator = new AXPromptOrchestrator(
                    promptRepository,
                    languageProvider,
                    new LogLineKnowledgePlanner(logKnowledgeLines),
                    null
            );
            AXMemoryRetriever memoryRetriever = new AXMemoryRetriever(memorySystem, ragClient);
            AXTurnOrchestrator orchestrator = new AXTurnOrchestrator(
                    () -> scope,
                    new AXDialogueInputMapper(),
                    new AXInputNormalizer(),
                    maintenanceCoordinator,
                    new AXDynamicFactClient(axAdapter, 2_000L),
                    new AXContextCollector(memorySystem, recentDialogueSystem),
                    new AXLlmPromptRequestBuilder(promptOrchestrator),
                    AXContextBudget.DEFAULT,
                    new AXRuntimeLlmBudgetResolver(primitiveClient, windowPolicy),
                    llmClient,
                    new AXSessionController(axAdapter),
                    memorySystem,
                    recentDialogueSystem,
                    new AXOutputProcessor(axAdapter, outputSettings(), chatSink),
                    memoryRetriever,
                    new AXTurnStatusPublisher(axAdapter)
            );
            return new SmokeHarness(runtime, orchestrator, memorySystem, recentDialogueSystem, scope, chatSink, promptRecorder, taskPromptRepository, memoryRetriever, logKnowledgeLines);
        }

        RoundRecord runRound(int round, String question) throws Exception {
            chatSink.reset();
            DialogueDeliveryPayload delivery = delivery(round, question);
            TianshuEnvelope envelope = EnvelopeBuilder.commandToCapability(
                    IaProtocolAdapter.SOURCE_ID,
                    AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                    PayloadType.DIALOGUE_DELIVERY,
                    delivery
            ).build();
            orchestrator.startTurn(envelope, delivery);
            String answer = chatSink.await(180_000L);
            waitForMaintenance();
            AXMemorySnapshot memory = snapshotMemory();
            AXMemoryRetrievalResult diagnosticRetrieval = retrieveForDiagnostics(round, question);
            return new RoundRecord(
                    round,
                    question,
                    answer,
                    recentDialogueSystem.snapshot(scope).turns().size(),
                    diagnosticRetrieval.blocks().size(),
                    memory.recentPlayerMemoryBlocks().size(),
                    diagnosticRetrieval.traces()
            );
        }

        int recentDialogueSize() {
            return recentDialogueSystem.snapshot(scope).turns().size();
        }

        private AXMemoryRetrievalResult retrieveForDiagnostics(int round, String question) throws Exception {
            if (memoryRetriever == null) {
                return AXMemoryRetrievalResult.empty();
            }
            AXMemoryRetrievalRequest request = new AXMemoryRetrievalRequest(
                    scope,
                    new AXRequest("longrun.diagnostic." + round, question, ""),
                    AXContextBudget.DEFAULT.maxMemoryItems(),
                    AXContextBudget.DEFAULT.memoryTokenBudget()
            );
            return memoryRetriever.retrieveAsync(request).get(180, TimeUnit.SECONDS);
        }

        AXMemorySnapshot snapshotMemory() {
            return memorySystem.load(scope);
        }

        MemoryStoreSnapshot storageSnapshot() {
            List<AXEventVector> vectors = memorySystem.vectors().loadAllNamespaces(scope);
            List<AXMemoryRetrievalIndexSnapshot> indexSnapshots = vectors.stream()
                    .map(AXEventVector::embeddingNamespace)
                    .filter(namespace -> namespace != null && !namespace.isBlank())
                    .distinct()
                    .map(namespace -> memorySystem.retrievalIndexSnapshots().load(scope, namespace).orElse(null))
                    .filter(Objects::nonNull)
                    .toList();
            return new MemoryStoreSnapshot(
                    memorySystem.stmBlocks().loadAll(scope),
                    memorySystem.events().loadAll(scope),
                    vectors,
                    indexSnapshots
            );
        }

        PromptRecorder promptRecorder() {
            return promptRecorder;
        }

        AXMemoryTaskPromptRepository taskPromptRepository() {
            return taskPromptRepository;
        }

        List<String> logKnowledgeLines() {
            return logKnowledgeLines;
        }

        private void waitForMaintenance() {
            try {
                Thread.sleep(750L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static DialogueDeliveryPayload delivery(int round, String question) {
            long now = System.currentTimeMillis();
            return new DialogueDeliveryPayload(
                    "ax-longrun-session",
                    "ax-longrun-request-" + round + "-" + UUID.randomUUID(),
                    "longrun-player",
                    "ax-longrun-turn-" + round,
                    question,
                    question,
                    List.of(),
                    List.of(),
                    List.of(),
                    DialogueInteractionHints.empty(),
                    DialogueContextSnapshot.empty("longrun-player"),
                    now,
                    now + TimeUnit.MINUTES.toMillis(10)
            );
        }

        private static AXOutputSettings outputSettings() {
            return () -> AXOutputMode.UI_ONLY;
        }
    }

    private static final class PromptRecorder {
        private final LLMService service;
        private final List<LLMPromptRequestPayload> requests = new CopyOnWriteArrayList<>();
        private final List<PromptResponseRecord> responses = new CopyOnWriteArrayList<>();

        private PromptRecorder(LLMService service) {
            this.service = service;
        }

        void handle(TianshuEnvelope envelope, ProtocolContext context) {
            if (!(envelope.payload() instanceof LLMPromptRequestPayload payload)) {
                context.complete(envelope.envelopeId());
                return;
            }
            requests.add(payload);
            CompletableFuture.runAsync(() -> {
                try {
                    LLMRequest request = LLMRequest.ofMessage(payload.chunks().stream()
                            .flatMap(chunk -> chunk.messageContent().stream())
                            .map(message -> switch (message.role()) {
                                case "system" -> MessageItem.system(message.content());
                                case "assistant" -> MessageItem.assistant(message.content());
                                default -> MessageItem.user(message.content());
                            })
                            .toList());
                    request.setMaxTokens(payload.maxTokens());
                    request.setThinking(payload.thinking());
                    request.setStream(false);
                    request.setLane(payload.lane());
                    request.setTaskPriority(payload.taskPriority());
                    request.setTaskPreemptible(payload.taskPreemptible());
                    String text = request.isTaskLane()
                            ? service.submitTask(request).get(180, TimeUnit.SECONDS)
                            : service.chat(request).text();
                    responses.add(new PromptResponseRecord(payload.requestId(), payload.lane(), true, text, ""));
                    context.submit(EnvelopeBuilder.responseTo(
                            "module.llm.ax-longrun",
                            envelope,
                            PayloadType.LLM_PROMPT_RESULT,
                            LLMPromptResultPayload.completed(payload.requestId(), text == null ? "" : text)
                    ).build());
                    context.complete(envelope.envelopeId());
                } catch (Throwable t) {
                    context.submit(EnvelopeBuilder.responseTo(
                            "module.llm.ax-longrun",
                            envelope,
                            PayloadType.LLM_PROMPT_RESULT,
                            LLMPromptResultPayload.failed(payload.requestId(), "AX_LONGRUN_LLM_FAILURE", t.getMessage())
                    ).build());
                    responses.add(new PromptResponseRecord(payload.requestId(), payload.lane(), false, "", t.getMessage()));
                    context.complete(envelope.envelopeId());
                }
            });
        }

        List<LLMPromptRequestPayload> requests() {
            return requests;
        }

        List<PromptResponseRecord> responses() {
            return responses;
        }
    }

    private static final class RecordingChatSink implements AXChatOutputSink {
        private final StringBuilder text = new StringBuilder();
        private CompletableFuture<String> completion = new CompletableFuture<>();

        void reset() {
            text.setLength(0);
            completion = new CompletableFuture<>();
        }

        String await(long timeoutMillis) throws Exception {
            return completion.get(timeoutMillis, TimeUnit.MILLISECONDS).strip();
        }

        @Override
        public void append(AXOutputContext context, String value) {
            text.append(value);
        }

        @Override
        public void complete(AXOutputContext context, String fullText) {
            if (!completion.isDone()) {
                completion.complete(fullText == null ? text.toString() : fullText);
            }
        }

        @Override
        public void fail(AXOutputContext context, String reason) {
            if (!completion.isDone()) {
                completion.completeExceptionally(new IllegalStateException(reason));
            }
        }
    }

    private static void registerLlmCapability(ProtocolRuntime runtime, PromptRecorder recorder) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.ax-longrun",
                List.of(new CapabilityDescriptor(
                        ProtocolCapabilities.LLM_REQUEST,
                        PayloadType.LLM_PROMPT_REQUEST,
                        LLMPromptRequestPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.REQUEST),
                        Priority.LOW,
                        CompletionPolicy.MANUAL_COMPLETE
                )),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        ), recorder::handle);
    }

    private static void registerPrimitiveCapability(ProtocolRuntime runtime, LLMService service) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.primitive.ax-longrun",
                List.of(new CapabilityDescriptor(
                        ProtocolCapabilities.LLM_PRIMITIVE_QUERY,
                        PayloadType.LLM_PRIMITIVE_QUERY,
                        LLMPrimitiveQueryPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.REQUEST),
                        Priority.LOW,
                        CompletionPolicy.MANUAL_COMPLETE
                )),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        ), (envelope, context) -> {
            if (!(envelope.payload() instanceof LLMPrimitiveQueryPayload payload)) {
                context.complete(envelope.envelopeId());
                return;
            }
            CompletableFuture.runAsync(() -> {
                LLMPrimitiveResultPayload result = switch (payload.queryType()) {
                    case LLMPrimitiveQueryPayload.QUERY_TYPE_EMBED ->
                            service.embedResponse(payload.requestId(), payload.texts(), payload.includeVector(), payload.includeEmbeddingDetails());
                    case LLMPrimitiveQueryPayload.QUERY_TYPE_STATUS ->
                            service.runtimeSnapshotResponse(payload.requestId());
                    case LLMPrimitiveQueryPayload.QUERY_TYPE_TOKEN_COUNT ->
                            service.tokenCountResponse(payload.requestId(), LLMRequest.ofMessage(MessageItem.user(payload.text())));
                    default -> LLMPrimitiveResultPayload.failed(
                            payload.requestId(),
                            payload.queryType(),
                            "UNSUPPORTED_PRIMITIVE_QUERY",
                            "Unsupported primitive query: " + payload.queryType()
                    );
                };
                context.submit(EnvelopeBuilder.responseTo(
                        "module.llm.primitive.ax-longrun",
                        envelope,
                        PayloadType.LLM_PRIMITIVE_RESULT,
                        result
                ).build());
                context.complete(envelope.envelopeId());
            });
        });
    }

    private static void registerPresenceContextCapability(ProtocolRuntime runtime) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        AtomicInteger counter = new AtomicInteger();
        runtime.registerModule(new ModuleDescriptor(
                "module.presence.ax-longrun",
                List.of(new CapabilityDescriptor(
                        ProtocolCapabilities.PRESENCE_QUERY_CONTEXT,
                        PayloadType.PRESENCE_CONTEXT_QUERY,
                        PresenceContextQueryPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.REQUEST),
                        Priority.LOW,
                        CompletionPolicy.MANUAL_COMPLETE
                )),
                defaults.threadPolicy(),
                defaults.cancellationScope(),
                defaults.failurePolicy(),
                defaults.deliveryPolicy(),
                defaults.cancellable(),
                defaults.supportsStreaming(),
                defaults.maxConcurrency(),
                defaults.queueCapacity()
        ), (envelope, context) -> {
            int index = counter.incrementAndGet();
            String requestId = envelope.payload() instanceof PresenceContextQueryPayload payload
                    ? payload.requestId()
                    : "presence-" + index;
            context.submit(EnvelopeBuilder.responseTo(
                    "module.presence.ax-longrun",
                    envelope,
                    PayloadType.PRESENCE_CONTEXT_SNAPSHOT,
                    PresenceContextSnapshotPayload.success(
                            requestId,
                            List.of(
                                    new PresenceContextSnapshotPayload.FactPayload(
                                            "dimension",
                                            "当前维度：主世界；观察轮次：" + index,
                                            80,
                                            "presence.longrun",
                                            "minecraft:overworld",
                                            List.of("dimension"),
                                            System.currentTimeMillis(),
                                            30_000L
                                    ),
                                    new PresenceContextSnapshotPayload.FactPayload(
                                            "focus",
                                            "玩家正在检查日志、记忆压缩、提示词组装的长期迭代效果。",
                                            95,
                                            "presence.longrun",
                                            "ax-longrun",
                                            List.of("debug", "memory"),
                                            System.currentTimeMillis(),
                                            30_000L
                                    )
                            )
                    )
            ).build());
            context.complete(envelope.envelopeId());
        });
    }
}
