package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval;

import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPrimitiveClient;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXEventVector;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
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
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXMemoryRetrievalTieredTest {
    @TempDir
    Path tempDir;

    @Test
    void selectsAcrossHotWarmColdTiersByScoreThresholds() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        registerPrimitiveProvider(runtime);
        AXMemorySystem memorySystem = new AXMemorySystem(
                new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                new AXJsonStore(new TestLlmSupport.FakeGameEnvironment())
        );
        AXScope scope = new AXScope("player", "save:Test", "Test", AXScopeKind.LOCAL_WORLD, true);

        AXStmBlock hotBlock = block(scope, "hot", "玩家把钻石镐放进末影箱。");
        AXStmBlock warmBlock = block(scope, "warm", "玩家提到过末影箱。");
        AXStmBlock coldBlock = block(scope, "cold", "玩家曾经在矿洞里挖矿。");
        memorySystem.appendStmBlock(scope, hotBlock);
        memorySystem.appendStmBlock(scope, warmBlock);
        memorySystem.appendStmBlock(scope, coldBlock);

        AXMemoryEvent hotEvent = event(scope, hotBlock, "玩家把钻石镐放进末影箱。");
        AXMemoryEvent warmEvent = event(scope, warmBlock, "玩家提到过末影箱。");
        AXMemoryEvent coldEvent = event(scope, coldBlock, "玩家曾经在矿洞里挖矿。");
        memorySystem.events().appendAll(scope, List.of(hotEvent, warmEvent, coldEvent));

        memorySystem.vectors().appendAll(scope, List.of(
                vector(hotEvent, new float[]{0.95F, 0.05F}),
                vector(warmEvent, new float[]{0.50F, 0.50F}),
                vector(coldEvent, new float[]{0.20F, 0.80F})
        ));

        AXMemoryRetriever retriever = new AXMemoryRetriever(memorySystem, new AXLlmPrimitiveClient(new AXProtocolAdapter(runtime), 2_000L));
        AtomicReference<AXMemoryRetrievalResult> result = new AtomicReference<>();

        retriever.retrieve(new AXMemoryRetrievalRequest(scope, new AXRequest("query", "钻石镐在哪里？", ""), 3, 1000), result::set);

        await(() -> result.get() != null);

        assertEquals(3, result.get().blocks().size(), "三层各出一个候选，应返回 3 个 block");
        List<String> blockIds = result.get().blocks().stream().map(view -> view.block().id()).toList();
        assertTrue(blockIds.contains(hotBlock.id()), "应包含 Hot 层候选");
        assertTrue(blockIds.contains(warmBlock.id()), "应包含 Warm 层候选");
        assertTrue(blockIds.contains(coldBlock.id()), "应包含 Cold 层候选");
    }

    @Test
    void hotTierHasPriorityWhenBlockBudgetIsLimited() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        registerPrimitiveProvider(runtime);
        AXMemorySystem memorySystem = new AXMemorySystem(
                new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                new AXJsonStore(new TestLlmSupport.FakeGameEnvironment())
        );
        AXScope scope = new AXScope("player", "save:Test", "Test", AXScopeKind.LOCAL_WORLD, true);

        AXStmBlock hotBlock = block(scope, "hot", "玩家把钻石镐放进末影箱。");
        AXStmBlock warmBlock = block(scope, "warm", "玩家提到过末影箱。");
        AXStmBlock coldBlock = block(scope, "cold", "玩家曾经在矿洞里挖矿。");
        memorySystem.appendStmBlock(scope, hotBlock);
        memorySystem.appendStmBlock(scope, warmBlock);
        memorySystem.appendStmBlock(scope, coldBlock);

        AXMemoryEvent hotEvent = event(scope, hotBlock, "玩家把钻石镐放进末影箱。");
        AXMemoryEvent warmEvent = event(scope, warmBlock, "玩家提到过末影箱。");
        AXMemoryEvent coldEvent = event(scope, coldBlock, "玩家曾经在矿洞里挖矿。");
        memorySystem.events().appendAll(scope, List.of(hotEvent, warmEvent, coldEvent));

        memorySystem.vectors().appendAll(scope, List.of(
                vector(hotEvent, new float[]{0.95F, 0.05F}),
                vector(warmEvent, new float[]{0.50F, 0.50F}),
                vector(coldEvent, new float[]{0.20F, 0.80F})
        ));

        AXMemoryRetriever retriever = new AXMemoryRetriever(memorySystem, new AXLlmPrimitiveClient(new AXProtocolAdapter(runtime), 2_000L));
        AtomicReference<AXMemoryRetrievalResult> result = new AtomicReference<>();

        retriever.retrieve(new AXMemoryRetrievalRequest(scope, new AXRequest("query", "钻石镐在哪里？", ""), 1, 1000), result::set);

        await(() -> result.get() != null);

        assertEquals(1, result.get().blocks().size(), "预算 1 时只应返回 1 个 block");
        assertEquals(hotBlock.id(), result.get().blocks().get(0).block().id(), "应优先返回 Hot 层候选");
    }

    @Test
    void coldTierCandidatesBelowThresholdAreExcluded() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        registerPrimitiveProvider(runtime);
        AXMemorySystem memorySystem = new AXMemorySystem(
                new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                new AXJsonStore(new TestLlmSupport.FakeGameEnvironment())
        );
        AXScope scope = new AXScope("player", "save:Test", "Test", AXScopeKind.LOCAL_WORLD, true);

        AXStmBlock hotBlock = block(scope, "hot", "玩家把钻石镐放进末影箱。");
        AXStmBlock noiseBlock = block(scope, "noise", "完全不相关的内容。");
        memorySystem.appendStmBlock(scope, hotBlock);
        memorySystem.appendStmBlock(scope, noiseBlock);

        AXMemoryEvent hotEvent = event(scope, hotBlock, "玩家把钻石镐放进末影箱。");
        AXMemoryEvent noiseEvent = event(scope, noiseBlock, "完全不相关的内容。");
        memorySystem.events().appendAll(scope, List.of(hotEvent, noiseEvent));

        memorySystem.vectors().appendAll(scope, List.of(
                vector(hotEvent, new float[]{0.95F, 0.05F}),
                vector(noiseEvent, new float[]{0.05F, 0.95F})
        ));

        AXMemoryRetriever retriever = new AXMemoryRetriever(memorySystem, new AXLlmPrimitiveClient(new AXProtocolAdapter(runtime), 2_000L));
        AtomicReference<AXMemoryRetrievalResult> result = new AtomicReference<>();

        retriever.retrieve(new AXMemoryRetrievalRequest(scope, new AXRequest("query", "钻石镐在哪里？", ""), 5, 1000), result::set);

        await(() -> result.get() != null);

        assertEquals(1, result.get().blocks().size(), "低于 Cold 阈值的候选应被排除");
        assertEquals(hotBlock.id(), result.get().blocks().get(0).block().id());
    }

    private static AXStmBlock block(AXScope scope, String seed, String content) {
        return new AXStmBlock(
                "stm_" + seed, "", scope.worldId(), System.currentTimeMillis(),
                1L, 2L, "", "", 1, 0, content, List.of()
        );
    }

    private static AXMemoryEvent event(AXScope scope, AXStmBlock stm, String fact) {
        return new AXMemoryEvent("", fact, "", stm.id(), "stm_fact", scope.worldId(), "", "", false, 10L, 10L, 0, List.of());
    }

    private static AXEventVector vector(AXMemoryEvent event, float[] vector) {
        return new AXEventVector(event.id(), event.factHash(), "test-embed", "test-embed:v1", vector.length, vector, 10L);
    }

    private static void registerPrimitiveProvider(ProtocolRuntime runtime) {
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.registerModule(new ModuleDescriptor(
                "module.llm.test",
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
        ), AXMemoryRetrievalTieredTest::handlePrimitive);
    }

    private static void handlePrimitive(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof LLMPrimitiveQueryPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "invalid", null);
            return;
        }
        if (LLMPrimitiveQueryPayload.QUERY_TYPE_STATUS.equals(payload.queryType())) {
            context.submit(com.rheinmetal.tianshu.protocol.EnvelopeBuilder.responseTo(
                    "module.llm.test",
                    envelope,
                    PayloadType.LLM_PRIMITIVE_RESULT,
                    LLMPrimitiveResultPayload.runtime(payload.requestId(), new LLMRuntimeSnapshotPayload(
                            true, true, true, 2, false, false, 0, 0, 4096,
                            true, true, true, 0, 0, 0,
                            "test-model", "test-profile", "test-embed", "test-embed:v1", "",
                            System.currentTimeMillis()
                    ))
            ).build());
        } else if (LLMPrimitiveQueryPayload.QUERY_TYPE_EMBED.equals(payload.queryType())) {
            context.submit(com.rheinmetal.tianshu.protocol.EnvelopeBuilder.responseTo(
                    "module.llm.test",
                    envelope,
                    PayloadType.LLM_PRIMITIVE_RESULT,
                    LLMPrimitiveResultPayload.embed(payload.requestId(), List.of(
                            LLMPrimitiveResultPayload.EmbedResultPayload.of(
                                    payload.texts().isEmpty() ? "" : payload.texts().get(0),
                                    new float[]{1.0F, 0.0F},
                                    true, "test-embed", "test-embed:v1"
                            )
                    ))
            ).build());
        }
        context.complete(envelope.envelopeId());
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
