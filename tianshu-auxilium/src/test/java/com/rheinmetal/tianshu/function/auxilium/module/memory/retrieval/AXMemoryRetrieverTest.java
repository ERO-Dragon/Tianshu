package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextTier;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmRagClient;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.index.AXMemoryRetrievalIndexSnapshot;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                new AXJsonStore(new FakeGameEnvironment())
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

        retriever.retrieve(new AXMemoryRetrievalRequest(scope, new AXRequest("query", "钻石镐在哪里？", ""), 1, 1000, AXContextTier.COMPACT), result::set);

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
                new AXJsonStore(new FakeGameEnvironment())
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

        retriever.retrieve(new AXMemoryRetrievalRequest(scope, new AXRequest("query", "钻石镐在哪里？", ""), 2, 1000, AXContextTier.COMPACT), result::set);

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
                new AXJsonStore(new FakeGameEnvironment())
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

        retriever.retrieve(new AXMemoryRetrievalRequest(scope, new AXRequest("query", "钻石镐在哪里？", ""), 3, 1000, AXContextTier.COMPACT), result::set);

        await(() -> result.get() != null);
        assertEquals(List.of(s1.id(), s2.id(), s3.id()), result.get().blocks().stream().map(view -> view.block().id()).toList());
    }

    @Test
    void widerContextTierFollowsTwoChainHopsInsteadOfOne() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AXMemoryRagTestProvider.register(runtime);
        AXLlmRagClient ragClient = new AXLlmRagClient(new AXProtocolAdapter(runtime), 2_000L);
        AXMemorySystem memorySystem = new AXMemorySystem(
                new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                new AXJsonStore(new FakeGameEnvironment())
        );
        AXScope scope = scope();
        AXStmBlock s1 = linkedBlock(scope, "s1", "", "stm_s2", "玩家出发去找村庄。");
        AXStmBlock s2 = linkedBlock(scope, "s2", "stm_s1", "stm_s3", "玩家在村庄铁匠铺找到钻石镐。");
        AXStmBlock s3 = linkedBlock(scope, "s3", "stm_s2", "stm_s4", "玩家把钻石镐放进末影箱。");
        AXStmBlock s4 = linkedBlock(scope, "s4", "stm_s3", "stm_s5", "玩家离开村庄继续赶路。");
        AXStmBlock s5 = linkedBlock(scope, "s5", "stm_s4", "", "玩家回到了自己的基地。");
        memorySystem.stmBlocks().rewrite(scope, List.of(s1, s2, s3, s4, s5));
        AXMemoryEvent anchor = event(scope, s3, "玩家把钻石镐放进末影箱。");
        memorySystem.events().appendAll(scope, List.of(anchor));
        memorySystem.vectors().appendAll(scope, List.of(vector(anchor, new float[]{1.0F, 0.0F})));
        new AXMemoryRagProjectionService(memorySystem, ragClient).project(scope, "test-embed:v1").join();
        AXMemoryRetriever retriever = new AXMemoryRetriever(memorySystem, ragClient);
        AtomicReference<AXMemoryRetrievalResult> compact = new AtomicReference<>();
        AtomicReference<AXMemoryRetrievalResult> standard = new AtomicReference<>();

        retriever.retrieve(
                new AXMemoryRetrievalRequest(scope, new AXRequest("query", "钻石镐在哪里？", ""), 5, 1000, AXContextTier.COMPACT),
                compact::set
        );
        retriever.retrieve(
                new AXMemoryRetrievalRequest(scope, new AXRequest("query", "钻石镐在哪里？", ""), 5, 1000, AXContextTier.STANDARD),
                standard::set
        );

        await(() -> compact.get() != null && standard.get() != null);
        assertEquals(List.of(s2.id(), s3.id(), s4.id()), blockIds(compact.get()), "紧凑档只跟一跳");
        assertEquals(List.of(s1.id(), s2.id(), s3.id(), s4.id(), s5.id()), blockIds(standard.get()), "标准档跟两跳");
    }

    private static List<String> blockIds(AXMemoryRetrievalResult result) {
        return result == null ? List.of() : result.blocks().stream().map(view -> view.block().id()).toList();
    }

    @Test
    void returnsEmptyResultWhenPrimitiveProviderIsMissing() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AXMemoryRetriever retriever = new AXMemoryRetriever(
                new AXMemorySystem(
                        new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                        new AXJsonStore(new FakeGameEnvironment())
                ),
                new AXLlmRagClient(new AXProtocolAdapter(runtime), 2_000L)
        );
        AtomicReference<AXMemoryRetrievalResult> result = new AtomicReference<>();

        retriever.retrieve(new AXMemoryRetrievalRequest(scope(), new AXRequest("query", "钻石镐在哪里？", ""), 1, 1000, AXContextTier.COMPACT), result::set);

        await(() -> result.get() != null);
        assertEquals(0, result.get().blocks().size());
    }

    @Test
    void ranksByTheThreeFactorProductNotByRelevanceAlone() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AXMemoryRagTestProvider.register(runtime);
        AXLlmRagClient ragClient = new AXLlmRagClient(new AXProtocolAdapter(runtime), 2_000L);
        AXMemorySystem memorySystem = memorySystem();
        AXScope scope = scope();
        AXStmBlock stale = block(scope, "stale", "玩家很久以前随口说过一句关于钻石的话。");
        AXStmBlock important = block(scope, "important", "玩家在 128, 64, -302 建造了传送门。");
        memorySystem.appendStmBlock(scope, stale);
        memorySystem.appendStmBlock(scope, important);
        long now = System.currentTimeMillis();
        // 相关性：stale 更高；其余两因子：important 全胜（新鲜 + 重要）。
        AXMemoryEvent staleEvent = event(scope, stale, "玩家随口说过一句关于钻石的话。", 3, now - 40L * 86_400_000L, List.of("1"));
        AXMemoryEvent importantEvent = event(scope, important, "玩家建造了传送门。", 10, now, List.of("1"));
        memorySystem.events().appendAll(scope, List.of(staleEvent, importantEvent));
        memorySystem.vectors().appendAll(scope, List.of(
                vector(staleEvent, new float[]{1.0F, 0.0F}),
                vector(importantEvent, new float[]{0.90F, 0.10F})
        ));
        new AXMemoryRagProjectionService(memorySystem, ragClient).project(scope, "test-embed:v1").join();
        AXMemoryRetriever retriever = new AXMemoryRetriever(memorySystem, ragClient);
        AtomicReference<AXMemoryRetrievalResult> result = new AtomicReference<>();

        retriever.retrieve(new AXMemoryRetrievalRequest(scope, new AXRequest("query", "传送门在哪？", ""), 1, 1000, AXContextTier.COMPACT), result::set);

        await(() -> result.get() != null);
        assertEquals(important.id(), result.get().blocks().get(0).block().id(),
                "相关性 0.90 的新事件必须压过相关性 1.0 但四十天前的弱事件");
    }

    @Test
    void pinnedImportanceStaysRetrievableWhenAnEquallyOldOrdinaryEventFallsAway() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AXMemoryRagTestProvider.register(runtime);
        AXLlmRagClient ragClient = new AXLlmRagClient(new AXProtocolAdapter(runtime), 2_000L);
        AXMemorySystem memorySystem = memorySystem();
        AXScope scope = scope();
        AXStmBlock pinned = block(scope, "pinned", "玩家的家在平原村庄东侧。");
        AXStmBlock ordinary = block(scope, "ordinary", "玩家整理了一下箱子。");
        memorySystem.appendStmBlock(scope, pinned);
        memorySystem.appendStmBlock(scope, ordinary);
        long old = System.currentTimeMillis() - 400L * 86_400_000L;
        AXMemoryEvent pinnedEvent = event(scope, pinned, "玩家的家在平原村庄东侧。", AXMemoryScoring.PINNED_IMPORTANCE, old, List.of("1"));
        AXMemoryEvent ordinaryEvent = event(scope, ordinary, "玩家整理了一下箱子。", 5, old, List.of("1"));
        memorySystem.events().appendAll(scope, List.of(pinnedEvent, ordinaryEvent));
        memorySystem.vectors().appendAll(scope, List.of(
                vector(pinnedEvent, new float[]{0.6F, 0.8F}),
                vector(ordinaryEvent, new float[]{0.8F, 0.2F})
        ));
        new AXMemoryRagProjectionService(memorySystem, ragClient).project(scope, "test-embed:v1").join();
        AXMemoryRetriever retriever = new AXMemoryRetriever(memorySystem, ragClient);
        AtomicReference<AXMemoryRetrievalResult> result = new AtomicReference<>();

        // 只给一个名额：谁的分更高谁上。钉子事件的 recency 地板被抬到 0.50，普通事件落到 0.05。
        retriever.retrieve(new AXMemoryRetrievalRequest(scope, new AXRequest("query", "我家在哪？", ""), 1, 1000, AXContextTier.COMPACT), result::set);

        await(() -> result.get() != null);
        List<String> ids = result.get().blocks().stream().map(view -> view.block().id()).toList();
        assertEquals(List.of(pinned.id()), ids, "同样四百天前，重要性 7 以上的记忆必须压过普通记忆");
    }

    @Test
    void sameInputTwiceProducesTheSameOrdering() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        AXMemoryRagTestProvider.register(runtime);
        AXLlmRagClient ragClient = new AXLlmRagClient(new AXProtocolAdapter(runtime), 2_000L);
        AXMemorySystem memorySystem = memorySystem();
        AXScope scope = scope();
        long now = System.currentTimeMillis();
        List<AXStmBlock> blocks = new ArrayList<>();
        List<AXMemoryEvent> events = new ArrayList<>();
        List<AXEventVector> vectors = new ArrayList<>();
        // 三条完全同分（同向量、同发生时间、同重要性）的事件：能区分它们的只有 stmId。
        // 插入顺序是 b → a → c，正确的并列胜者是 id 最小的 stm_tie-a。
        for (String seed : List.of("tie-b", "tie-a", "tie-c")) {
            AXStmBlock block = block(scope, seed, seed + " 的并列事实。");
            blocks.add(block);
            AXMemoryEvent event = event(scope, block, seed + " 的并列事实。", 6, now, List.of("1"));
            events.add(event);
            vectors.add(vector(event, new float[]{0.8F, 0.2F}));
        }
        for (AXStmBlock block : blocks) {
            memorySystem.appendStmBlock(scope, block);
        }
        memorySystem.events().appendAll(scope, events);
        memorySystem.vectors().appendAll(scope, vectors);
        new AXMemoryRagProjectionService(memorySystem, ragClient).project(scope, "test-embed:v1").join();
        AXMemoryRetriever retriever = new AXMemoryRetriever(memorySystem, ragClient);

        List<String> first = retrieveSelectedIds(retriever, scope);
        List<String> second = retrieveSelectedIds(retriever, scope);

        assertEquals(first, second, "同输入两次检索的结果必须一致");
        assertEquals(List.of(blocks.get(1).id()), first,
                "同分时以 stmId 升序决胜：id 最小的 stm_tie-a 拿到唯一名额");
    }

    private List<String> retrieveSelectedIds(AXMemoryRetriever retriever, AXScope scope) {
        AtomicReference<AXMemoryRetrievalResult> result = new AtomicReference<>();
        retriever.retrieve(new AXMemoryRetrievalRequest(scope, new AXRequest("query", "并列事实是什么？", ""), 1, 1000, AXContextTier.COMPACT), result::set);
        await(() -> result.get() != null);
        return result.get().blocks().stream().map(view -> view.block().id()).toList();
    }

    private AXMemorySystem memorySystem() {
        return new AXMemorySystem(
                new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                new AXJsonStore(new FakeGameEnvironment())
        );
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

    /**
     * 夹具事件：发生时间取"刚刚"，因为 recency 现在是指数衰减——用纪元时间（10L）会让任何事件都贴住
     * 0.05 地板，测出来的只是地板而不是打分。
     */
    private static AXMemoryEvent event(AXScope scope, AXStmBlock stm, String fact) {
        return event(scope, stm, fact, AXMemoryEvent.DEFAULT_IMPORTANCE, System.currentTimeMillis(), List.of());
    }

    private static AXMemoryEvent event(
            AXScope scope,
            AXStmBlock stm,
            String fact,
            int importance,
            long happenedAtMillis,
            List<String> turnRefs
    ) {
        return new AXMemoryEvent("", fact, "", stm.id(), "stm_fact", scope.worldId(), "", "", false,
                System.currentTimeMillis(), happenedAtMillis, List.of(), turnRefs, importance);
    }

    private static AXEventVector vector(AXMemoryEvent event, float[] vector) {
        return new AXEventVector(event.id(), event.factHash(), "test-embed", "test-embed:v1", vector.length, vector, System.currentTimeMillis());
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
