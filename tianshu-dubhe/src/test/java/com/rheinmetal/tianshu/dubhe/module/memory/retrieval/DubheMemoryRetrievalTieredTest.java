package com.rheinmetal.tianshu.dubhe.module.memory.retrieval;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.dubhe.DubheProtocolAdapter;
import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.core.context.DubheContextTier;
import com.rheinmetal.tianshu.dubhe.core.llm.DubheLlmRagClient;
import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemorySystem;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheEventVector;
import com.rheinmetal.tianshu.dubhe.module.memory.event.DubheMemoryEvent;
import com.rheinmetal.tianshu.dubhe.module.memory.shortterm.DubheStmBlock;
import com.rheinmetal.tianshu.dubhe.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.scope.DubheScopeKind;
import com.rheinmetal.tianshu.dubhe.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.storage.DubheStorageLayout;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheMemoryRetrievalTieredTest {
    @TempDir
    Path tempDir;

    @Test
    void selectsAcrossHotWarmColdTiersByRelativeScoreCutoffs() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        DubheMemoryRagTestProvider.register(runtime);
        DubheLlmRagClient ragClient = new DubheLlmRagClient(new DubheProtocolAdapter(runtime), 2_000L);
        DubheMemorySystem memorySystem = new DubheMemorySystem(
                new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                new DubheJsonStore(new FakeGameEnvironment())
        );
        DubheScope scope = new DubheScope("player", "save:Test", "Test", DubheScopeKind.LOCAL_WORLD, true);

        DubheStmBlock hotBlock = block(scope, "hot", "玩家把钻石镐放进末影箱。");
        DubheStmBlock warmBlock = block(scope, "warm", "玩家提到过末影箱。");
        DubheStmBlock coldBlock = block(scope, "cold", "玩家曾经在矿洞里挖矿。");
        memorySystem.appendStmBlock(scope, hotBlock);
        memorySystem.appendStmBlock(scope, warmBlock);
        memorySystem.appendStmBlock(scope, coldBlock);

        DubheMemoryEvent hotEvent = event(scope, hotBlock, "玩家把钻石镐放进末影箱。");
        DubheMemoryEvent warmEvent = event(scope, warmBlock, "玩家提到过末影箱。");
        DubheMemoryEvent coldEvent = event(scope, coldBlock, "玩家曾经在矿洞里挖矿。");
        memorySystem.events().appendAll(scope, List.of(hotEvent, warmEvent, coldEvent));

        memorySystem.vectors().appendAll(scope, List.of(
                vector(hotEvent, new float[]{0.95F, 0.05F}),
                vector(warmEvent, new float[]{0.50F, 0.50F}),
                vector(coldEvent, new float[]{0.20F, 0.80F})
        ));

        new DubheMemoryRagProjectionService(memorySystem, ragClient).project(scope, "test-embed:v1").join();
        DubheMemoryRetriever retriever = new DubheMemoryRetriever(memorySystem, ragClient);
        AtomicReference<DubheMemoryRetrievalResult> result = new AtomicReference<>();

        retriever.retrieve(new DubheMemoryRetrievalRequest(scope, new DubheRequest("query", "钻石镐在哪里？", ""), 3, 1000, DubheContextTier.COMPACT), result::set);

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
        DubheMemoryRagTestProvider.register(runtime);
        DubheLlmRagClient ragClient = new DubheLlmRagClient(new DubheProtocolAdapter(runtime), 2_000L);
        DubheMemorySystem memorySystem = new DubheMemorySystem(
                new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                new DubheJsonStore(new FakeGameEnvironment())
        );
        DubheScope scope = new DubheScope("player", "save:Test", "Test", DubheScopeKind.LOCAL_WORLD, true);

        DubheStmBlock hotBlock = block(scope, "hot", "玩家把钻石镐放进末影箱。");
        DubheStmBlock warmBlock = block(scope, "warm", "玩家提到过末影箱。");
        DubheStmBlock coldBlock = block(scope, "cold", "玩家曾经在矿洞里挖矿。");
        memorySystem.appendStmBlock(scope, hotBlock);
        memorySystem.appendStmBlock(scope, warmBlock);
        memorySystem.appendStmBlock(scope, coldBlock);

        DubheMemoryEvent hotEvent = event(scope, hotBlock, "玩家把钻石镐放进末影箱。");
        DubheMemoryEvent warmEvent = event(scope, warmBlock, "玩家提到过末影箱。");
        DubheMemoryEvent coldEvent = event(scope, coldBlock, "玩家曾经在矿洞里挖矿。");
        memorySystem.events().appendAll(scope, List.of(hotEvent, warmEvent, coldEvent));

        memorySystem.vectors().appendAll(scope, List.of(
                vector(hotEvent, new float[]{0.95F, 0.05F}),
                vector(warmEvent, new float[]{0.50F, 0.50F}),
                vector(coldEvent, new float[]{0.20F, 0.80F})
        ));

        new DubheMemoryRagProjectionService(memorySystem, ragClient).project(scope, "test-embed:v1").join();
        DubheMemoryRetriever retriever = new DubheMemoryRetriever(memorySystem, ragClient);
        AtomicReference<DubheMemoryRetrievalResult> result = new AtomicReference<>();

        retriever.retrieve(new DubheMemoryRetrievalRequest(scope, new DubheRequest("query", "钻石镐在哪里？", ""), 1, 1000, DubheContextTier.COMPACT), result::set);

        await(() -> result.get() != null);

        assertEquals(1, result.get().blocks().size(), "预算 1 时只应返回 1 个 block");
        assertEquals(hotBlock.id(), result.get().blocks().get(0).block().id(), "应优先返回 Hot 层候选");
    }

    @Test
    void candidatesBelowTheColdFloorAreExcluded() {
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        DubheMemoryRagTestProvider.register(runtime);
        DubheLlmRagClient ragClient = new DubheLlmRagClient(new DubheProtocolAdapter(runtime), 2_000L);
        DubheMemorySystem memorySystem = new DubheMemorySystem(
                new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                new DubheJsonStore(new FakeGameEnvironment())
        );
        DubheScope scope = new DubheScope("player", "save:Test", "Test", DubheScopeKind.LOCAL_WORLD, true);

        DubheStmBlock hotBlock = block(scope, "hot", "玩家把钻石镐放进末影箱。");
        DubheStmBlock noiseBlock = block(scope, "noise", "完全不相关的内容。");
        memorySystem.appendStmBlock(scope, hotBlock);
        memorySystem.appendStmBlock(scope, noiseBlock);

        DubheMemoryEvent hotEvent = event(scope, hotBlock, "玩家把钻石镐放进末影箱。");
        DubheMemoryEvent noiseEvent = event(scope, noiseBlock, "完全不相关的内容。");
        memorySystem.events().appendAll(scope, List.of(hotEvent, noiseEvent));

        memorySystem.vectors().appendAll(scope, List.of(
                vector(hotEvent, new float[]{0.95F, 0.05F}),
                // 噪声与查询的余弦相似度约 0.0143（>= RAG 搜索地板 0.01，但仍低于 Cold 绝对地板
                // COLD_FLOOR=0.02 × 时间衰减），因此这条测试验证的是"低于 Cold 线被排除"，
                // 而不是被 RAG 地板提前滤掉。
                vector(noiseEvent, new float[]{1.0F, 70.0F})
        ));

        new DubheMemoryRagProjectionService(memorySystem, ragClient).project(scope, "test-embed:v1").join();
        DubheMemoryRetriever retriever = new DubheMemoryRetriever(memorySystem, ragClient);
        AtomicReference<DubheMemoryRetrievalResult> result = new AtomicReference<>();

        retriever.retrieve(new DubheMemoryRetrievalRequest(scope, new DubheRequest("query", "钻石镐在哪里？", ""), 5, 1000, DubheContextTier.COMPACT), result::set);

        await(() -> result.get() != null);

        assertEquals(1, result.get().blocks().size(), "低于 Cold 阈值的候选应被排除");
        assertEquals(hotBlock.id(), result.get().blocks().get(0).block().id());
    }

    private static DubheStmBlock block(DubheScope scope, String seed, String content) {
        return new DubheStmBlock(
                "stm_" + seed, "", scope.worldId(), System.currentTimeMillis(),
                1L, 2L, "", "", 1, 0, content, List.of()
        );
    }

    private static DubheMemoryEvent event(DubheScope scope, DubheStmBlock stm, String fact) {
        return new DubheMemoryEvent("", fact, "", stm.id(), "stm_fact", scope.worldId(), "", "", false, System.currentTimeMillis(), System.currentTimeMillis(), List.of(), List.of(), DubheMemoryEvent.DEFAULT_IMPORTANCE);
    }

    private static DubheEventVector vector(DubheMemoryEvent event, float[] vector) {
        return new DubheEventVector(event.id(), event.factHash(), "test-embed", "test-embed:v1", vector.length, vector, 10L);
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
