package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXRecentDialogueSystemCheckpointTest {
    @TempDir
    Path tempDir;

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
        return new AXRawTurnCheckpointStore(layout, new AXJsonStore(new TestLlmSupport.FakeGameEnvironment()));
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
