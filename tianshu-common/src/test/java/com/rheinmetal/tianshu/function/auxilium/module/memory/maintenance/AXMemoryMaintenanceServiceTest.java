package com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance;

import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmClient;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPrimitiveClient;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSystem;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
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
import com.rheinmetal.tianshu.protocol.payload.LLMRuntimeSnapshotPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurnBatch;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;

class AXMemoryMaintenanceServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void taskRequestsAreUnlimitedThinkingAndCleanedBeforeStorage() throws Exception {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXScope scope = new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);
        AXMemoryWindowPolicy policy = new AXMemoryWindowPolicy(
                8000,
                4800,
                3200,
                480,
                1440,
                1200,
                480,
                960,
                240,
                4800,
                3200,
                600,
                600,
                3600,
                20,
                4000,
                10,
                200,
                28000,
                120000,
                2,
                0L
        );
        AXMemorySystem memorySystem = new AXMemorySystem(
                new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                new AXJsonStore(new TestLlmSupport.FakeGameEnvironment()),
                policy
        );
        AXRecentDialogueSystem recentDialogueSystem = new AXRecentDialogueSystem(policy, (requestId, role, content) -> java.util.OptionalInt.of(3000));
        recentDialogueSystem.append(scope, AXRawTurn.dialogue(scope, "user", "我在看资源重载后的日志。", "session", "turn-1"));
        recentDialogueSystem.append(scope, AXRawTurn.dialogue(scope, "assistant", "我会关注缓存和模块加载。", "session", "turn-1"));

        RecordingLlmModule llm = new RecordingLlmModule();
        registerLlm(runtime, llm);
        registerPrimitive(runtime);
        AXMemoryMaintenanceService service = new AXMemoryMaintenanceService(
                adapter,
                memorySystem,
                recentDialogueSystem,
                new AXLlmClient(adapter),
                new AXLlmPrimitiveClient(adapter, 2_000L),
                new AXMemoryTaskPromptRepository(
                        new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                        AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN)
                )
        );

        assertTrue(service.requestMaintenance(scope));
        await(() -> memorySystem.stmBlocks().loadAll(scope).size() == 1
                && memorySystem.events().loadAll(scope).size() == 2);

        assertEquals(2, llm.requests.size());
        LLMPromptRequestPayload compression = llm.requests.get(0);
        LLMPromptRequestPayload extraction = llm.requests.get(1);
        assertEquals(0, compression.maxTokens());
        assertTrue(compression.thinking());
        assertFalse(compression.captureThinkingContent());
        assertEquals(0, extraction.maxTokens());
        assertTrue(extraction.thinking());
        assertFalse(extraction.captureThinkingContent());

        String stm = memorySystem.stmBlocks().loadAll(scope).get(0).content();
        String fact = memorySystem.events().loadAll(scope).get(0).fact();
        String secondFact = memorySystem.events().loadAll(scope).get(1).fact();
        assertEquals("玩家在调试资源重载日志，并关注缓存和模块加载。", stm);
        assertEquals("玩家关注资源重载后的缓存和模块加载。", fact);
        assertEquals("AX \u5173\u6ce8\u6a21\u5757\u52a0\u8f7d\u3002", secondFact);
        assertTrue(memorySystem.events().loadAll(scope).stream()
                .allMatch(event -> memorySystem.stmBlocks().loadAll(scope).get(0).id().equals(event.stmId())));
    }

    @Test
    void existingStmForRawBatchSkipsCompressionAndConfirmsAfterEvents() throws Exception {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        AXScope scope = new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);
        AXMemoryWindowPolicy policy = maintenancePolicy();
        AXMemorySystem memorySystem = new AXMemorySystem(
                new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                new AXJsonStore(new TestLlmSupport.FakeGameEnvironment()),
                policy
        );
        AXRecentDialogueSystem recentDialogueSystem = new AXRecentDialogueSystem(policy, (requestId, role, content) -> java.util.OptionalInt.of(3000));
        recentDialogueSystem.append(scope, AXRawTurn.dialogue(scope, "user", "我在看资源重载后的日志。", "session", "turn-1"));
        recentDialogueSystem.append(scope, AXRawTurn.dialogue(scope, "assistant", "我会关注缓存和模块加载。", "session", "turn-1"));
        AXRawTurnBatch batch = recentDialogueSystem.selectCompressionBatch(scope);
        memorySystem.appendStmBlock(scope, new AXStmBlock(
                batch.stmId(),
                "",
                scope.worldId(),
                System.currentTimeMillis(),
                batch.sourceFromMillis(),
                batch.sourceToMillis(),
                "",
                "",
                batch.turns().size(),
                12,
                "玩家在调试资源重载日志，并关注缓存和模块加载。",
                List.of()
        ));

        RecordingLlmModule llm = new RecordingLlmModule(1);
        registerLlm(runtime, llm);
        registerPrimitive(runtime);
        AXMemoryMaintenanceService service = new AXMemoryMaintenanceService(
                adapter,
                memorySystem,
                recentDialogueSystem,
                new AXLlmClient(adapter),
                new AXLlmPrimitiveClient(adapter, 2_000L),
                new AXMemoryTaskPromptRepository(
                        new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                        AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN)
                )
        );

        assertTrue(service.requestMaintenance(scope));
        await(() -> memorySystem.events().loadAll(scope).size() == 2
                && recentDialogueSystem.snapshot(scope).turns().isEmpty());

        assertEquals(1, memorySystem.stmBlocks().loadAll(scope).size());
        assertEquals(1, llm.requests.size());
        assertEquals(batch.stmId(), memorySystem.stmBlocks().loadAll(scope).get(0).id());
        assertTrue(memorySystem.events().loadAll(scope).stream()
                .allMatch(event -> batch.stmId().equals(event.stmId())));
    }

    @Test
    void extractionPrefersJsonArrayAndFallsBackToLines() {
        AXMemoryFactExtractionParser parser = new AXMemoryFactExtractionParser();

        // 单行纯文本降级为一条事实，去除行尾标点
        assertEquals(List.of("\u73a9\u5bb6\u5173\u6ce8\u8d44\u6e90\u91cd\u8f7d\u540e\u7684\u7f13\u5b58"), parser.parse("\u73a9\u5bb6\u5173\u6ce8\u8d44\u6e90\u91cd\u8f7d\u540e\u7684\u7f13\u5b58\u3002"));
        // 多行纯文本降级，剥除 markdown 围栏与控制符
        assertEquals(
                List.of("\u73a9\u5bb6\u5173\u6ce8\u7f13\u5b58", "AX \u5173\u6ce8\u6a21\u5757\u52a0\u8f7d"),
                parser.parse("```\n\u73a9\u5bb6\u5173\u6ce8\u7f13\u5b58\u3002\n1. AX \u5173\u6ce8\u6a21\u5757\u52a0\u8f7d\u3002\n```")
        );
        // 单个 JSON 对象（非数组）仍被拒绝
        assertTrue(parser.parse("{\"fact\":\"\u73a9\u5bb6\u5173\u6ce8\u8d44\u6e90\u91cd\u8f7d\u540e\u7684\u7f13\u5b58\u3002\"}").isEmpty());
        // JSONL（多行对象）仍被拒绝
        assertTrue(parser.parse("{\"fact\":\"\u73a9\u5bb6\u5173\u6ce8\u7f13\u5b58\u3002\"}\n{\"fact\":\"AX \u5173\u6ce8\u6a21\u5757\u52a0\u8f7d\u3002\"}").isEmpty());
        // JSON 数组但元素含额外字段仍被拒绝
        assertTrue(parser.parse("[{\"fact\":\"\u73a9\u5bb6\u5173\u6ce8\u7f13\u5b58\u3002\",\"tag\":\"debug\"}]").isEmpty());
        // JSON 数组但 fact 含 Unicode 替换符仍被拒绝
        assertTrue(parser.parse("[{\"fact\":\"\u73a9\u5bb6\u5173\u6ce8 \uFFFD \u7f13\u5b58\u3002\"}]").isEmpty());
        // JSON 数组重复 fact 去重
        assertEquals(List.of("\u73a9\u5bb6\u5173\u6ce8\u7f13\u5b58\u3002"), parser.parse("[{\"fact\":\"\u73a9\u5bb6\u5173\u6ce8\u7f13\u5b58\u3002\"},{\"fact\":\"\u73a9\u5bb6\u5173\u6ce8\u7f13\u5b58\u3002\"}]"));
    }

    private static void registerLlm(ProtocolRuntime runtime, RecordingLlmModule llm) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.memory-test",
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
        ), llm::handle);
    }

    private static void registerPrimitive(ProtocolRuntime runtime) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.primitive.memory-test",
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
            LLMPrimitiveQueryPayload payload = (LLMPrimitiveQueryPayload) envelope.payload();
            LLMPrimitiveResultPayload result = LLMPrimitiveQueryPayload.QUERY_TYPE_TOKEN_COUNT.equals(payload.queryType())
                    ? LLMPrimitiveResultPayload.tokenCount(payload.requestId(), 12)
                    : LLMPrimitiveResultPayload.runtime(payload.requestId(), LLMRuntimeSnapshotPayload.unavailable());
            context.submit(EnvelopeBuilder.responseTo(
                    "module.llm.primitive.memory-test",
                    envelope,
                    PayloadType.LLM_PRIMITIVE_RESULT,
                    result
            ).build());
            context.complete(envelope.envelopeId());
        });
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static AXMemoryWindowPolicy maintenancePolicy() {
        return new AXMemoryWindowPolicy(
                8000,
                4800,
                3200,
                480,
                1440,
                1200,
                480,
                960,
                240,
                4800,
                3200,
                600,
                600,
                3600,
                20,
                4000,
                10,
                200,
                28000,
                120000,
                2,
                0L
        );
    }

    private static final class RecordingLlmModule {
        private final List<LLMPromptRequestPayload> requests = new ArrayList<>();
        private final AtomicInteger count = new AtomicInteger();

        RecordingLlmModule() {
            this(0);
        }

        RecordingLlmModule(int initialCount) {
            count.set(Math.max(0, initialCount));
        }

        void handle(TianshuEnvelope envelope, ProtocolContext context) {
            LLMPromptRequestPayload payload = (LLMPromptRequestPayload) envelope.payload();
            requests.add(payload);
            if (count.get() == 1) {
                count.incrementAndGet();
                String text = "[{\"fact\":\"\u73a9\u5bb6\u5173\u6ce8\u8d44\u6e90\u91cd\u8f7d\u540e\u7684\u7f13\u5b58\u548c\u6a21\u5757\u52a0\u8f7d\u3002\"},"
                        + "{\"fact\":\"AX \u5173\u6ce8\u6a21\u5757\u52a0\u8f7d\u3002\"}]";
                context.submit(EnvelopeBuilder.responseTo(
                        "module.llm.memory-test",
                        envelope,
                        PayloadType.LLM_PROMPT_RESULT,
                        LLMPromptResultPayload.completed(payload.requestId(), text, "\u62bd\u53d6\u4e8b\u5b9e\u601d\u8003\u8fc7\u7a0b", List.of(), LLMPromptResultPayload.TokenUsagePayload.empty())
                ).build());
                context.complete(envelope.envelopeId());
                return;
            }
            String text = count.getAndIncrement() == 0
                    ? "玩家在调试资源重载日志，并关注缓存和模块加载。"
                    : "玩家关注资源重载后的缓存和模块加载。";
            String thinkingContent = count.get() == 1 ? "压缩思考过程" : "抽取事实思考过程";
            context.submit(EnvelopeBuilder.responseTo(
                    "module.llm.memory-test",
                    envelope,
                    PayloadType.LLM_PROMPT_RESULT,
                    LLMPromptResultPayload.completed(payload.requestId(), text, thinkingContent, List.of(), LLMPromptResultPayload.TokenUsagePayload.empty())
            ).build());
            context.complete(envelope.envelopeId());
        }
    }
}
