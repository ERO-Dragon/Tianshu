package com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmClient;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPrimitiveClient;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssemblyBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptBuildContext;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemoryBlockView;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;
import com.rheinmetal.tianshu.function.auxilium.module.memory.summary.AXMemorySummaryBlock;
import com.rheinmetal.tianshu.function.auxilium.module.memory.summary.AXMemorySummaryGeneration;
import com.rheinmetal.tianshu.function.auxilium.module.memory.summary.AXMemorySummaryPromptContributor;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSystem;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurnBatch;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptProfile;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMRuntimeSnapshotPayload;
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
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两级窗口：单元压缩静默（不动原文、不动摘要块），压缩窗口装满才换代。
 *
 * <p>换代判据是 raw 窗口当前 token 数达到 {@code MAX_RAW_TOKENS = KEEP_MAX + COMPRESS_MAX}——
 * <b>不是</b>提示词水位 {@code compressionWatermarkTokenBudget}（那是整份 prompt 的尺子）。</p>
 */
class AXMemorySummarySwapTest {
    private static final String COMPACT_JSON = """
            {
              "schemaVersion": 2,
              "summary": "玩家在调试资源重载日志。",
              "eventChain": [
                { "event": "玩家关注资源重载后的缓存。", "importance": 6, "turnRefs": [1] }
              ]
            }
            """;

    @TempDir
    Path tempDir;

    @Test
    void windowBelowCapacityCompressesSilentlyWithoutATouchOnThePrompt() throws Exception {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        try {
            AXScope scope = scope();
            AXMemoryWindowPolicy policy = compactPolicy();
            AXMemorySystem memorySystem = new AXMemorySystem(layout(), jsonStore(), policy);
            AXRecentDialogueSystem recentDialogueSystem = recentDialogue(policy);
            appendRounds(recentDialogueSystem, scope, 3, 2_000);
            appendRounds(recentDialogueSystem, scope, 1, 300);
            int rawBefore = recentDialogueSystem.rawTokenCount(scope);
            AXMemorySummaryBlock blockBefore = memorySystem.summaryBlock(scope);
            registerLlm(runtime);
            registerPrimitive(runtime);
            AXMemoryMaintenanceService service = service(runtime, memorySystem, recentDialogueSystem);

            assertTrue(service.requestMaintenance(scope));
            await(() -> memorySystem.stmBlocks().loadAll(scope).size() == 1);

            assertFalse(recentDialogueSystem.shouldSwapGeneration(scope), "窗口远没装满");
            assertEquals(rawBefore, recentDialogueSystem.rawTokenCount(scope), "单元压缩不移出原文");
            assertEquals(blockBefore, memorySystem.summaryBlock(scope), "单元压缩不动摘要块");
            assertTrue(memorySystem.summaries().loadGenerations(scope).isEmpty());
            assertEquals(0, recentDialogueSystem.generation(scope));
        } finally {
            runtime.close();
        }
    }

    @Test
    void fullWindowPromotesTheUnitAndDropsOnlyCoveredRaw() throws Exception {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        try {
            AXScope scope = scope();
            AXMemoryWindowPolicy policy = compactPolicy();
            AXMemorySystem memorySystem = new AXMemorySystem(layout(), jsonStore(), policy);
            AXRecentDialogueSystem recentDialogueSystem = recentDialogue(policy);
            appendRounds(recentDialogueSystem, scope, 5, 2_000);
            appendRounds(recentDialogueSystem, scope, 1, 300);
            AXRawTurnBatch batch = select(memorySystem, recentDialogueSystem, scope);
            assertEquals(4, batch.turns().size(), "单元只装得下最旧的两组对话");
            int rawBefore = recentDialogueSystem.rawTokenCount(scope);
            assertTrue(recentDialogueSystem.shouldSwapGeneration(scope), "测试前提：压缩窗口已经装满");
            List<AXRawTurn> keepBefore = recentDialogueSystem.snapshot(scope).turns();
            registerLlm(runtime);
            registerPrimitive(runtime);
            AXMemoryMaintenanceService service = service(runtime, memorySystem, recentDialogueSystem);

            assertTrue(service.requestMaintenance(scope));
            await(() -> memorySystem.summaries().loadGenerations(scope).size() == 1);

            List<AXMemorySummaryGeneration> generations = memorySystem.summaries().loadGenerations(scope);
            assertEquals(1, generations.get(0).generation());
            assertEquals(List.of(batch.stmId()), generations.get(0).stmIds(), "本代唯一的单元被晋升");

            AXMemorySummaryBlock block = memorySystem.summaryBlock(scope);
            assertEquals(1, block.generation());
            assertEquals(1, block.summariesInBlock());
            assertEquals("玩家在调试资源重载日志。", block.units().get(0).content());

            assertEquals(1, recentDialogueSystem.generation(scope));
            assertEquals(rawBefore - batch.tokenCount(), recentDialogueSystem.rawTokenCount(scope),
                    "只剪掉被单元覆盖的那一段 raw");
            assertEquals(keepBefore, recentDialogueSystem.snapshot(scope).turns(), "保持窗口一轮都不许少");
        } finally {
            runtime.close();
        }
    }

    @Test
    void turnsArrivingAfterASwapDoNotChangeTheBlock() throws Exception {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        try {
            AXScope scope = scope();
            AXMemoryWindowPolicy policy = compactPolicy();
            AXMemorySystem memorySystem = new AXMemorySystem(layout(), jsonStore(), policy);
            AXRecentDialogueSystem recentDialogueSystem = recentDialogue(policy);
            appendRounds(recentDialogueSystem, scope, 5, 2_000);
            appendRounds(recentDialogueSystem, scope, 1, 300);
            registerLlm(runtime);
            registerPrimitive(runtime);
            AXMemoryMaintenanceService service = service(runtime, memorySystem, recentDialogueSystem);
            assertTrue(service.requestMaintenance(scope));
            await(() -> memorySystem.summaries().loadGenerations(scope).size() == 1);
            AXMemorySummaryBlock afterSwap = memorySystem.summaryBlock(scope);

            appendRounds(recentDialogueSystem, scope, 1, 300);

            assertEquals(afterSwap, memorySystem.summaryBlock(scope), "next-generation raw 不进块：等下一次换代");
            assertEquals(1, recentDialogueSystem.generation(scope));
        } finally {
            runtime.close();
        }
    }

    @Test
    void renderingTheSameBlockTwiceIsByteIdentical() {
        AXScope scope = scope();
        AXMemorySummaryBlock block = new AXMemorySummaryBlock(
                3,
                List.of(
                        view(scope, "stm_b", "较新的一段。", 4_000L),
                        view(scope, "stm_a", "较早的一段。", 2_000L)
                ),
                20,
                1
        );
        AXMemorySnapshot memory = AXMemorySnapshot.empty().withSummaryBlock(block);

        String first = renderSummary(scope, memory);
        String second = renderSummary(scope, memory);

        assertEquals(first, second, "非换代轮次的摘要块必须逐字节稳定");
        assertTrue(first.contains("<memory_summary>"));
        assertTrue(first.indexOf("较新的一段。") < first.indexOf("较早的一段。"), "块内新→旧");
    }

    @Test
    void summaryBlockRendersNewestFirstWithTheConfiguredBudget() {
        AXScope scope = scope();
        AXMemorySummaryBlock block = new AXMemorySummaryBlock(
                2,
                List.of(view(scope, "stm_2", "第二代。", 3_000L), view(scope, "stm_1", "第一代。", 1_000L)),
                30,
                2
        );

        String rendered = renderSummary(scope, AXMemorySnapshot.empty().withSummaryBlock(block));

        assertTrue(rendered.contains("第二代。"));
        assertTrue(rendered.contains("第一代。"));
        assertTrue(rendered.indexOf("第二代。") < rendered.indexOf("第一代。"));
        assertFalse(rendered.contains("stm_2"), "只渲染摘要正文，不暴露内部 id");
    }

    private String renderSummary(AXScope scope, AXMemorySnapshot memory) {
        AXPromptAssemblyBuilder builder = new AXPromptAssemblyBuilder();
        new AXMemorySummaryPromptContributor().contribute(new AXPromptBuildContext(
                new AXRequest("request", "刚才发生了什么？", ""),
                new AXContextSnapshot(scope, memory, AXRecentDialogueSnapshot.empty(), List.of(), ""),
                AXContextBudget.DEFAULT,
                AXPromptLanguage.ZH_CN,
                AXPromptProfile.defaultFor(null, AXPromptLanguage.ZH_CN)
        ), builder);
        List<LLMPromptRequestPayload.MessageItemPayload> messages = builder.build().messages();
        return messages.isEmpty() ? "" : messages.get(0).content();
    }

    private AXMemoryBlockView view(AXScope scope, String id, String content, long sourceTo) {
        return AXMemoryBlockView.of(new AXStmBlock(
                id, "", scope.worldId(), 5_000L, sourceTo - 100L, sourceTo,
                "", "", 2, 10, content, List.of()));
    }

    private AXRawTurnBatch select(AXMemorySystem memorySystem, AXRecentDialogueSystem recentDialogueSystem, AXScope scope) {
        return recentDialogueSystem.selectCompressionBatch(scope, memorySystem.stmCoveredThroughTurnId(scope));
    }


    private void appendRounds(AXRecentDialogueSystem recentDialogueSystem, AXScope scope, int rounds, int tokens) {
        long seed = System.nanoTime();
        for (int index = 0; index < rounds; index++) {
            String turnId = "turn-" + tokens + "-" + seed + "-" + index;
            recentDialogueSystem.append(scope, turn(scope, "user", "第" + turnId + "问。", turnId, tokens));
            recentDialogueSystem.append(scope, turn(scope, "assistant", "第" + turnId + "答。", turnId, tokens));
        }
    }

    private AXRawTurn turn(AXScope scope, String role, String content, String turnId, int tokens) {
        return AXRawTurn.dialogue(scope, role, content, "session", turnId).withTokenCount(tokens);
    }

    private AXScope scope() {
        return new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);
    }

    private AXStorageLayout layout() {
        return new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
    }

    private AXJsonStore jsonStore() {
        return new AXJsonStore(new FakeGameEnvironment());
    }

    /** 32K 档：KEEP_MAX 3277 / 单元 8192 / MAX_RAW 19990。 */
    private static AXMemoryWindowPolicy compactPolicy() {
        return AXMemoryWindowPolicy.fromBudget(32_768, 3, 60000L);
    }


    private static AXRecentDialogueSystem recentDialogue(AXMemoryWindowPolicy policy) {
        return new AXRecentDialogueSystem(policy, (requestId, role, content) -> OptionalInt.empty());
    }

    private AXMemoryMaintenanceService service(
            ProtocolRuntime runtime,
            AXMemorySystem memorySystem,
            AXRecentDialogueSystem recentDialogueSystem
    ) {
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);
        return new AXMemoryMaintenanceService(
                adapter,
                memorySystem,
                recentDialogueSystem,
                new AXLlmClient(adapter),
                new AXLlmPrimitiveClient(adapter, 2_000L),
                new AXMemoryTaskPromptRepository(layout(), AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN))
        );
    }

    private static void registerLlm(ProtocolRuntime runtime) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.memory-swap-test",
                List.of(new CapabilityDescriptor(
                        "LLM.REQUEST",
                        PayloadType.of("LLM_PROMPT_REQUEST"),
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
        ), (TianshuEnvelope envelope, ProtocolContext context) -> {
            LLMPromptRequestPayload payload = (LLMPromptRequestPayload) envelope.payload();
            context.submit(EnvelopeBuilder.responseTo(
                    "module.llm.memory-swap-test",
                    envelope,
                    PayloadType.of("LLM_PROMPT_RESULT"),
                    LLMPromptResultPayload.completed(payload.requestId(), COMPACT_JSON)
            ).build());
            context.complete(envelope.envelopeId());
        });
    }

    private static void registerPrimitive(ProtocolRuntime runtime) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.primitive.memory-swap-test",
                List.of(new CapabilityDescriptor(
                        "LLM.PRIMITIVE_QUERY",
                        PayloadType.of("LLM_PRIMITIVE_QUERY"),
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
                    "module.llm.primitive.memory-swap-test",
                    envelope,
                    PayloadType.of("LLM_PRIMITIVE_RESULT"),
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


}
