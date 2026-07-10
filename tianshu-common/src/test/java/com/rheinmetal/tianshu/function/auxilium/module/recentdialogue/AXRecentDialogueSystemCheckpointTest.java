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
    void compressionConfirmationCheckpointsOnlyRemainingRawTurns() {
        AXStorageLayout layout = layout();
        AXScope scope = scope();
        AXRecentDialogueSystem system = system(layout);
        system.append(scope, turn(scope, "u1", "user", "第一问", "turn-1", 10));
        system.append(scope, turn(scope, "a1", "assistant", "第一答", "turn-1", 10));
        system.append(scope, turn(scope, "u2", "user", "第二问", "turn-2", 10));
        system.append(scope, turn(scope, "a2", "assistant", "第二答", "turn-2", 10));

        AXRawTurnBatch batch = system.selectCompressionBatch(scope);
        system.confirmConsumed(scope, batch);

        AXRawTurnCheckpointStore store = checkpointStore(layout);
        assertEquals(2, store.load(scope).size());
        assertEquals("u2", store.load(scope).get(0).id());
        assertEquals("a2", store.load(scope).get(1).id());
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
                0,
                100,
                300,
                250,
                100,
                1000,
                50,
                1000,
                0,
                125,
                125,
                750,
                20,
                1000,
                10,
                15,
                10000,
                10000,
                3,
                60000L
        );
    }
}
