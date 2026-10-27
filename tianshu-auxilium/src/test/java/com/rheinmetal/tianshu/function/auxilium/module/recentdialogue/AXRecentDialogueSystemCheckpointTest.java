package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXRecentDialogueSystemCheckpointTest {
    @TempDir
    Path tempDir;

    /**
     * 写前落盘：不调用任何 checkpoint，玩家那句话也已经在盘上。
     *
     * <p>这是崩溃安全的全部要害——进程被强杀时来不及做任何收尾，能救回的只有"已经写下去的东西"。</p>
     */
    @Test
    void everyTurnReachesTheDiskBeforeTheTurnEnds() {
        AXStorageLayout layout = layout();
        AXScope scope = scope();
        AXRecentDialogueSystem system = system(layout);

        system.append(scope, turn(scope, "u1", "user", "崩溃前问的那句话", "turn-1", 10));

        AXRawTurnCheckpointStore store = checkpointStore(layout);
        assertEquals(1, store.load(scope).size());
        assertEquals("崩溃前问的那句话", store.load(scope).get(0).content());
    }

    /**
     * 崩溃留下的"没人答的最后一问"在下次启动时被清掉。
     *
     * <p>一次完成的轮次总以答句或游戏事件结尾；以提问结尾就意味着那一轮没走完，它不该成为下一轮对话的开头，
     * 也不该被当成玩家真的问过的话。</p>
     */
    @Test
    void anUnansweredTailQuestionIsPrunedOnRestart() {
        AXStorageLayout layout = layout();
        AXScope scope = scope();
        AXRecentDialogueSystem system = system(layout);
        system.append(scope, turn(scope, "a1", "assistant", "上一轮的答句", "turn-0", 10));
        system.append(scope, turn(scope, "u1", "user", "没人回答的问题", "turn-1", 10));

        AXRecentDialogueSystem restarted = system(layout);
        assertTrue(restarted.pruneUnansweredTail(scope));

        assertEquals(1, restarted.snapshot(scope).turns().size(), "前面那一轮完整对话留下");
        assertEquals(1, checkpointStore(layout).load(scope).size(), "盘上也要一起清掉");
        assertFalse(restarted.pruneUnansweredTail(scope), "清过之后再清没有意义");
    }

    /**
     * 失败回滚：这一轮写进去的用户/助手轮次撤掉，期间的游戏事件保留。
     *
     * <p>游戏事件写的是世界自己发生的事，与这一问成不成功无关；一次失败的提问不该顺带把事实抹掉。</p>
     */
    @Test
    void rollingBackATurnKeepsTheGameEventsOfThatTurn() {
        AXStorageLayout layout = layout();
        AXScope scope = scope();
        AXRecentDialogueSystem system = system(layout);
        system.append(scope, turn(scope, "u0", "user", "上一问", "turn-0", 10));
        system.append(scope, turn(scope, "a0", "assistant", "上一答", "turn-0", 10));
        int mark = system.mark(scope);

        system.append(scope, turn(scope, "u1", "user", "这一问没人答", "turn-1", 10));
        system.append(scope, AXRawTurn.worldEvent(scope, "附近的僵尸死了", System.currentTimeMillis()));

        assertEquals(1, system.rollbackTo(scope, mark), "只撤掉那一问，游戏事件不在撤除范围内");

        AXRecentDialogueSystem restarted = system(layout);
        assertEquals(3, restarted.snapshot(scope).turns().size(), "两轮旧对话 + 一条游戏事件");
        assertEquals("附近的僵尸死了", restarted.snapshot(scope).turns().get(2).content());
    }

    @Test
    void generationSwapCheckpointsTheRemainingRawTurns() {
        AXStorageLayout layout = layout();
        AXScope scope = scope();
        AXRecentDialogueSystem system = system(layout);
        system.append(scope, turn(scope, "u1", "user", "第一问", "turn-1", 10));
        system.append(scope, turn(scope, "a1", "assistant", "第一答", "turn-1", 10));
        system.append(scope, turn(scope, "u2", "user", "第二问", "turn-2", 10));
        system.append(scope, turn(scope, "a2", "assistant", "第二答", "turn-2", 10));

        // 单元压缩不动原文（这里没有单元压缩，原文自然一份不少）；换代才把已覆盖的那段移出。
        int removed = system.swapGeneration(scope, 1, "a1");

        assertEquals(2, removed);
        AXRawTurnCheckpointStore store = checkpointStore(layout);
        assertEquals(2, store.load(scope).size());
        assertEquals("u2", store.load(scope).get(0).id());
        assertEquals("a2", store.load(scope).get(1).id());
    }

    @Test
    void generationAdvancesOnlyOnGenerationSwap() {
        AXStorageLayout layout = layout();
        AXScope scope = scope();
        AXRecentDialogueSystem system = system(layout);
        system.append(scope, turn(scope, "u1", "user", "第一问", "turn-1", 10));
        system.append(scope, turn(scope, "a1", "assistant", "第一答", "turn-1", 10));
        system.append(scope, turn(scope, "u2", "user", "第二问", "turn-2", 10));
        system.append(scope, turn(scope, "a2", "assistant", "第二答", "turn-2", 10));

        assertEquals(0, system.generation(scope));
        assertEquals(0, system.generation(scope), "没有换代时不得推进代次");

        assertEquals(0, system.generation(scope), "单元压缩是静默的：它不改 prompt，也不推进代次");
        assertEquals(2, system.snapshot(scope).turns().size(), "保持窗口就是那两轮，压缩不动它");

        system.swapGeneration(scope, 1, "a1");
        assertEquals(1, system.generation(scope), "换代必须被记录，它解释了唯一预期的一次全 miss");
        system.swapGeneration(scope, 1, "a1");
        assertEquals(1, system.generation(scope), "同一代次重复写入不得回退或重复累加");
        system.swapGeneration(scope, 4, "a1");
        assertEquals(4, system.generation(scope), "代次以账本给出的值为准");
    }

    @Test
    void checkpointAllSavesCurrentRawTurnWindowForShutdownRestore() {
        AXStorageLayout layout = layout();
        AXScope scope = scope();
        AXRecentDialogueSystem system = system(layout);
        system.append(scope, turn(scope, "u1", "user", "退出前的问题", "turn-1", 10));
        system.append(scope, turn(scope, "a1", "assistant", "退出前的回答", "turn-1", 10));

        system.checkpointAll();

        assertTrue(Files.isRegularFile(layout.rawTurnCheckpointFile(scope)));
        AXRecentDialogueSystem restored = system(layout);
        assertEquals(2, restored.snapshot(scope).turns().size());
        assertEquals("u1", restored.snapshot(scope).turns().get(0).id());
        assertEquals("a1", restored.snapshot(scope).turns().get(1).id());
    }

    @Test
    void scopeSwitchCheckpointsPreviousWorldWindow() {
        AXStorageLayout layout = layout();
        AXScope firstWorld = scope();
        AXScope secondWorld = new AXScope("player", "save:Second World", "Second World", AXScopeKind.LOCAL_WORLD, true);
        AXRecentDialogueSystem system = system(layout);
        system.append(firstWorld, turn(firstWorld, "u1", "user", "第一个世界的问题", "turn-1", 10));

        system.append(secondWorld, turn(secondWorld, "u2", "user", "第二个世界的问题", "turn-1", 10));

        assertEquals(1, checkpointStore(layout).load(firstWorld).size());
        assertEquals("u1", checkpointStore(layout).load(firstWorld).get(0).id());
    }

    private AXRecentDialogueSystem system(AXStorageLayout layout) {
        return new AXRecentDialogueSystem(
                policy(),
                (requestId, role, content) -> OptionalInt.of(10),
                checkpointStore(layout)
        );
    }

    private AXRawTurnCheckpointStore checkpointStore(AXStorageLayout layout) {
        return new AXRawTurnCheckpointStore(layout, new AXJsonStore(new FakeGameEnvironment()));
    }

    private AXStorageLayout layout() {
        return new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
    }

    private AXScope scope() {
        return new AXScope("player", "save:Test World", "Test World", AXScopeKind.LOCAL_WORLD, true);
    }

    private AXRawTurn turn(AXScope scope, String id, String role, String content, String turnId, int tokens) {
        return new AXRawTurn(id, role, content, System.currentTimeMillis(), scope.worldId(), "session", turnId, tokens, content.length(), "");
    }

    private AXMemoryWindowPolicy policy() {
        return new AXMemoryWindowPolicy(
                1000,
                1000,
                100,
                1000,
                125,
                125,
                750,
                400,
                20,
                30,
                10,
                15,
                8_192,
                14_000,
                512,
                128,
                10000,
                10000,
                3,
                60000L
        );
    }
}
