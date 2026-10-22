package com.rheinmetal.tianshu.function.auxilium.module.memory.summary;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXMemorySummaryBlockTest {
    @TempDir
    Path tempDir;

    @Test
    void blockIsEmptyUntilAGenerationIsPromoted() {
        AXMemorySystem memorySystem = memorySystem();
        AXScope scope = scope();
        writeSummary(memorySystem, scope, "stm_a", 1_000L, "第一段摘要。", 100);

        AXMemorySummaryBlock block = memorySystem.summaryBlock(scope);

        assertTrue(block.isEmpty(), "没换代就没有块内容：单元摘要先留在库里");
        assertEquals(0, block.generation());
        assertTrue(memorySystem.summaries().loadGenerations(scope).isEmpty());
    }

    @Test
    void promotionPutsTheNewestUnitFirst() {
        AXMemorySystem memorySystem = memorySystem();
        AXScope scope = scope();
        writeSummary(memorySystem, scope, "stm_old", 1_000L, "最早的一段。", 10);
        writeSummary(memorySystem, scope, "stm_mid", 2_000L, "中间的一段。", 10);
        writeSummary(memorySystem, scope, "stm_new", 3_000L, "最新的一段。", 10);

        int generation = memorySystem.promoteSummaryGeneration(scope);

        assertEquals(1, generation);
        AXMemorySummaryBlock block = memorySystem.summaryBlock(scope);
        assertEquals(1, block.generation());
        assertEquals(
                List.of("stm_new", "stm_mid", "stm_old"),
                block.units().stream().map(view -> view.block().id()).toList(),
                "块内必须新→旧：最新的一段离 system 最近"
        );
        assertEquals(30, block.summaryBlockTokens());
        assertEquals(0, block.droppedTotal());
    }

    @Test
    void laterPromotionsAccumulateOnTopOfOlderOnes() {
        AXMemorySystem memorySystem = memorySystem();
        AXScope scope = scope();
        writeSummary(memorySystem, scope, "stm_a", 1_000L, "第一代的一段。", 10);
        assertEquals(1, memorySystem.promoteSummaryGeneration(scope));

        writeSummary(memorySystem, scope, "stm_b", 2_000L, "第二代的一段。", 10);
        writeSummary(memorySystem, scope, "stm_c", 3_000L, "第二代较新的一段。", 10);
        assertEquals(2, memorySystem.promoteSummaryGeneration(scope));

        AXMemorySummaryBlock block = memorySystem.summaryBlock(scope);
        assertEquals(2, block.generation());
        assertEquals(
                List.of("stm_c", "stm_b", "stm_a"),
                block.units().stream().map(view -> view.block().id()).toList(),
                "累积：新代在前，旧代整段排在后面"
        );
    }

    @Test
    void promotionIsIdempotentAndReturnsZeroWhenNothingIsPending() {
        AXMemorySystem memorySystem = memorySystem();
        AXScope scope = scope();
        writeSummary(memorySystem, scope, "stm_a", 1_000L, "唯一的一段。", 10);

        assertEquals(1, memorySystem.promoteSummaryGeneration(scope));
        assertEquals(0, memorySystem.promoteSummaryGeneration(scope), "没有待晋升单元时不得凭空造一个代次");
        assertEquals(1, memorySystem.summaryBlock(scope).units().size());
    }

    @Test
    void budgetDropsTheOldestFromTheBlockButKeepsItInTheLibrary() {
        // 压缩档的 SB 是 2048；两份 1500 token 的摘要装不下第二份。
        AXMemorySystem memorySystem = memorySystem();
        AXScope scope = scope();
        writeSummary(memorySystem, scope, "stm_old", 1_000L, "较早的一段摘要。", 1500);
        writeSummary(memorySystem, scope, "stm_new", 2_000L, "最新的一段摘要。", 1500);
        assertEquals(1, memorySystem.promoteSummaryGeneration(scope));

        AXMemorySummaryBlock block = memorySystem.summaryBlock(scope);

        assertEquals(List.of("stm_new"), block.units().stream().map(view -> view.block().id()).toList(),
                "超预算时从最旧淘汰");
        assertEquals(1, block.droppedTotal());
        assertEquals(1500, block.summaryBlockTokens());
        assertEquals(2, memorySystem.stmBlocks().loadAll(scope).size(), "被淘汰的摘要仍在记忆库里");
        assertEquals(2, memorySystem.summaries().loadGenerations(scope).get(0).stmIds().size(),
                "账本不改写：淘汰只影响渲染");
    }

    @Test
    void newestUnitIsRenderedEvenWhenItAloneExceedsTheBudget() {
        AXMemorySystem memorySystem = memorySystem();
        AXScope scope = scope();
        writeSummary(memorySystem, scope, "stm_huge", 1_000L, "单份就超预算的摘要。", 4_000);
        assertEquals(1, memorySystem.promoteSummaryGeneration(scope));

        AXMemorySummaryBlock block = memorySystem.summaryBlock(scope);

        assertEquals(1, block.units().size(), "最新那份永远保留，否则换代后边界接续会变成空");
    }

    @Test
    void addingAUnitWithoutASwapLeavesTheBlockByteIdentical() {
        AXMemorySystem memorySystem = memorySystem();
        AXScope scope = scope();
        writeSummary(memorySystem, scope, "stm_a", 1_000L, "已晋升的一段。", 10);
        assertEquals(1, memorySystem.promoteSummaryGeneration(scope));
        AXMemorySummaryBlock before = memorySystem.summaryBlock(scope);

        // 静默单元压缩：只往库里写摘要，不换代。
        writeSummary(memorySystem, scope, "stm_pending", 2_000L, "还没晋升的一段。", 10);

        assertEquals(before, memorySystem.summaryBlock(scope), "非换代轮次的块必须逐字节不变");
    }

    private AXMemorySystem memorySystem() {
        return new AXMemorySystem(layout(), new AXJsonStore(new FakeGameEnvironment()));
    }

    private AXStorageLayout layout() {
        return new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
    }

    private AXScope scope() {
        return new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);
    }

    private void writeSummary(AXMemorySystem memorySystem, AXScope scope, String id, long sourceTo, String content, int tokens) {
        assertTrue(memorySystem.stmBlocks().append(scope, new AXStmBlock(
                id, "", scope.worldId(), 5_000L, sourceTo - 100L, sourceTo,
                "", "", 2, tokens, content, List.of())));
    }

}
