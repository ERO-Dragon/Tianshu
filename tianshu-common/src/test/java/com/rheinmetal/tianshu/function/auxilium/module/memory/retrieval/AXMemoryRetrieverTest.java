package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval;

import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmRagClient;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.AXMemoryRetrievalRequest;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.AXMemoryRetrievalResult;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.AXMemoryRetriever;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.index.AXMemoryRetrievalIndexSnapshot;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXEventVector;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;

class AXMemoryRetrieverTest {
    @TempDir
    Path tempDir;

    @Test
    void ranksStmByContributionAcrossMultipleMatchingEvents() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AXMemoryRagTestProvider.register(runtime);
        AXLlmRagClient ragClient = new AXLlmRagClient(new AXProtocolAdapter(runtime), 2_000L);
        AXMemorySystem memorySystem = new AXMemorySystem(
                new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                new AXJsonStore(new TestLlmSupport.FakeGameEnvironment())
        );
        AXScope scope = new AXScope("player", "save:Test", "Test", AXScopeKind.LOCAL_WORLD, true);
        AXStmBlock target = block(scope, "target", "玩家在村庄找到铁匠铺，并把钻石镐放进末影箱。");
        AXStmBlock weaker = block(scope, "weaker", "玩家随口提到钻石。");
        memorySystem.appendStmBlock(scope, target);
        memorySystem.appendStmBlock(scope, weaker);
        AXMemoryEvent targetA = event(scope, target, "玩家找到村庄铁匠铺。");
        AXMemoryEvent targetB = event(scope, target, "玩家把钻石镐放进末影箱。");
        AXMemoryEvent weakerEvent = event(scope, weaker, "玩家提到钻石。");
        memorySystem.events().appendAll(scope, List.of(targetA, targetB, weakerEvent));
        memorySystem.vectors().appendAll(scope, List.of(
                vector(targetA, new float[]{1.0F, 0.0F}),
                vector(targetB, new float[]{0.8F, 0.2F}),
                vector(weakerEvent, new float[]{0.6F, 0.4F})
        ));
        new AXMemoryRagProjectionService(memorySystem, ragClient).project(scope, "test-embed:v1").join();
        AXMemoryRetriever retriever = new AXMemoryRetriever(memorySystem, ragClient);
        AtomicReference<AXMemoryRetrievalResult> result = new AtomicReference<>();

        retriever.retrieve(new AXMemoryRetrievalRequest(scope, new AXRequest("query", "钻石镐在哪里？", ""), 1, 1000), result::set);

        await(() -> result.get() != null);
        assertEquals(1, result.get().blocks().size());
        assertEquals(target.id(), result.get().blocks().get(0).block().id());
        assertEquals(1, result.get().traces().size());
        assertEquals(target.id(), result.get().traces().get(0).stmId());
        assertEquals(2, result.get().traces().get(0).eventHits().size());
        assertTrue(memorySystem.retrievalIndexSnapshots().load(scope, "test-embed:v1").isPresent());
        AXMemoryRetrievalIndexSnapshot snapshot = memorySystem.retrievalIndexSnapshots().load(scope, "test-embed:v1").orElseThrow();
        assertEquals("test-embed:v1", snapshot.embeddingNamespace());
        assertTrue(snapshot.l1Clusters().size() >= 1);
        assertTrue(snapshot.l2EffectiveMappings().size() >= 1);
    }

    @Test
    void returnsSelectedStmBlocksInTimelineOrderWithoutDuplicates() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AXMemoryRagTestProvider.register(runtime);
        AXLlmRagClient ragClient = new AXLlmRagClient(new AXProtocolAdapter(runtime), 2_000L);
        AXMemorySystem memorySystem = new AXMemorySystem(
                new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                new AXJsonStore(new TestLlmSupport.FakeGameEnvironment())
        );
        AXScope scope = scope();
        AXStmBlock s2 = block(scope, "s2", "玩家先到达村庄。");
        AXStmBlock s3 = block(scope, "s3", "玩家随后把钻石镐放进末影箱。");
        memorySystem.appendStmBlock(scope, s2);
        memorySystem.appendStmBlock(scope, s3);
        AXMemoryEvent e2 = event(scope, s2, "玩家到达村庄。");
        AXMemoryEvent e3a = event(scope, s3, "玩家把钻石镐放进末影箱。");
        AXMemoryEvent e3b = event(scope, s3, "玩家确认末影箱里有钻石镐。");
        memorySystem.events().appendAll(scope, List.of(e2, e3a, e3b));
        memorySystem.vectors().appendAll(scope, List.of(
                vector(e2, new float[]{0.75F, 0.25F}),
                vector(e3a, new float[]{1.0F, 0.0F}),
                vector(e3b, new float[]{0.9F, 0.1F})
        ));
        new AXMemoryRagProjectionService(memorySystem, ragClient).project(scope, "test-embed:v1").join();
        AXMemoryRetriever retriever = new AXMemoryRetriever(memorySystem, ragClient);
        AtomicReference<AXMemoryRetrievalResult> result = new AtomicReference<>();

        retriever.retrieve(new AXMemoryRetrievalRequest(scope, new AXRequest("query", "钻石镐在哪里？", ""), 2, 1000), result::set);

        await(() -> result.get() != null);
        assertEquals(List.of(s2.id(), s3.id()), result.get().blocks().stream().map(view -> view.block().id()).toList());
    }

    @Test
    void expandsConfidentHitToAdjacentStmChainInTimelineOrder() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AXMemoryRagTestProvider.register(runtime);
        AXLlmRagClient ragClient = new AXLlmRagClient(new AXProtocolAdapter(runtime), 2_000L);
        AXMemorySystem memorySystem = new AXMemorySystem(
                new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                new AXJsonStore(new TestLlmSupport.FakeGameEnvironment())
        );
        AXScope scope = scope();
        AXStmBlock s1 = linkedBlock(scope, "s1", "", "stm_s2", "玩家出发去找村庄。");
        AXStmBlock s2 = linkedBlock(scope, "s2", "stm_s1", "stm_s3", "玩家在村庄铁匠铺找到钻石镐。");
        AXStmBlock s3 = linkedBlock(scope, "s3", "stm_s2", "", "玩家把钻石镐放进末影箱。");
        memorySystem.stmBlocks().rewrite(scope, List.of(s1, s2, s3));
        AXMemoryEvent e2 = event(scope, s2, "玩家在村庄铁匠铺找到钻石镐。");
        memorySystem.events().appendAll(scope, List.of(e2));
        memorySystem.vectors().appendAll(scope, List.of(vector(e2, new float[]{1.0F, 0.0F})));
        new AXMemoryRagProjectionService(memorySystem, ragClient).project(scope, "test-embed:v1").join();
        AXMemoryRetriever retriever = new AXMemoryRetriever(memorySystem, ragClient);
        AtomicReference<AXMemoryRetrievalResult> result = new AtomicReference<>();

        retriever.retrieve(new AXMemoryRetrievalRequest(scope, new AXRequest("query", "钻石镐在哪里？", ""), 3, 1000), result::set);

        await(() -> result.get() != null);
        assertEquals(List.of(s1.id(), s2.id(), s3.id()), result.get().blocks().stream().map(view -> view.block().id()).toList());
    }

    @Test
    void returnsEmptyResultWhenPrimitiveProviderIsMissing() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AXMemoryRetriever retriever = new AXMemoryRetriever(
                new AXMemorySystem(
                        new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                        new AXJsonStore(new TestLlmSupport.FakeGameEnvironment())
                ),
                new AXLlmRagClient(new AXProtocolAdapter(runtime), 2_000L)
        );
        AtomicReference<AXMemoryRetrievalResult> result = new AtomicReference<>();

        retriever.retrieve(new AXMemoryRetrievalRequest(scope(), new AXRequest("query", "钻石镐在哪里？", ""), 1, 1000), result::set);

        await(() -> result.get() != null);
        assertEquals(0, result.get().blocks().size());
    }

    private static AXStmBlock block(AXScope scope, String seed, String content) {
        return new AXStmBlock(
                "stm_" + seed,
                "",
                scope.worldId(),
                System.currentTimeMillis(),
                1L,
                2L,
                "",
                "",
                1,
                0,
                content,
                List.of()
        );
    }

    private static AXStmBlock linkedBlock(AXScope scope, String seed, String previousId, String nextId, String content) {
        return new AXStmBlock(
                "stm_" + seed,
                "",
                scope.worldId(),
                System.currentTimeMillis(),
                1L,
                2L,
                previousId,
                nextId,
                1,
                0,
                content,
                List.of()
        );
    }

    private static AXMemoryEvent event(AXScope scope, AXStmBlock stm, String fact) {
        return new AXMemoryEvent("", fact, "", stm.id(), "stm_fact", scope.worldId(), "", "", false, 10L, 10L, 0, List.of());
    }

    private static AXEventVector vector(AXMemoryEvent event, float[] vector) {
        return new AXEventVector(event.id(), event.factHash(), "test-embed", "test-embed:v1", vector.length, vector, 10L);
    }

    private AXScope scope() {
        return new AXScope("player", "save:Test", "Test", AXScopeKind.LOCAL_WORLD, true);
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
