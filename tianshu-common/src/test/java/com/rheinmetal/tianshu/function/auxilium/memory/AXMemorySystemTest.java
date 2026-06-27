package com.rheinmetal.tianshu.function.auxilium.memory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.function.auxilium.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXMemorySystemTest {
    @TempDir
    Path tempDir;

    @Test
    void storesDurableMemoryArtifactsUnderAxCacheWorldRoot() throws Exception {
        AXStorageLayout layout = layout();
        AXMemorySystem memorySystem = memorySystem(layout);
        AXScope scope = scope();

        AXStmBlock block = new AXStmBlock(
                "",
                "",
                scope.worldId(),
                1000L,
                900L,
                950L,
                "",
                "",
                2,
                0,
                "玩家说自己正在寻找村庄，AX 提醒玩家带食物。",
                List.of("world_event:advancement")
        );
        AXMemoryEvent event = new AXMemoryEvent(
                "",
                "玩家说自己正在寻找村庄。",
                "",
                block.id(),
                "stm_fact",
                scope.worldId(),
                "minecraft:overworld",
                "10,64,20",
                true,
                1100L,
                950L,
                0,
                List.of("minecraft:village")
        );
        AXEventVector vector = new AXEventVector(
                event.id(),
                event.factHash(),
                "bge-small",
                "bge-small:v1",
                3,
                new float[]{0.1F, 0.2F, 0.3F},
                1200L
        );

        memorySystem.appendStmBlock(scope, block);
        memorySystem.appendMemoryEvent(scope, event);
        memorySystem.appendEventVector(scope, vector);

        assertTrue(Files.isRegularFile(layout.stmBlocksFile(scope)));
        assertTrue(Files.isRegularFile(layout.eventsFile(scope)));
        assertTrue(Files.isRegularFile(layout.eventVectorsFile(scope, "bge-small:v1")));
        assertTrue(Files.isRegularFile(layout.worldManifestFile(scope)));
        assertTrue(layout.stmBlocksFile(scope).startsWith(tempDir.resolve("module").resolve("ax").resolve("cache")));
        assertEquals(1, memorySystem.stmBlocks().loadAll(scope).size());
        assertEquals(1, memorySystem.events().loadAll(scope).size());
        assertEquals(1, memorySystem.vectors().load(scope, "bge-small:v1").size());

        JsonObject manifest = JsonParser.parseString(Files.readString(layout.worldManifestFile(scope), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(AXMemoryStorageManifestStore.LAYOUT_VERSION, manifest.get("layoutVersion").getAsInt());
        assertEquals(AXStmBlock.SCHEMA_VERSION, manifest.getAsJsonObject("schemas").get("stmBlock").getAsInt());
        assertEquals(AXAttachedWorldEvent.SCHEMA_VERSION, manifest.getAsJsonObject("schemas").get("attachedWorldEvent").getAsInt());
        assertEquals("stm_blocks/stm_blocks.jsonl", manifest.getAsJsonObject("files").get("stmBlocks").getAsString());
        assertEquals("events/attached_world_events.jsonl", manifest.getAsJsonObject("files").get("attachedWorldEvents").getAsString());
    }

    @Test
    void attachedWorldEventsAreStoredAppendOnlyAndDeduplicatedByKey() {
        AXStorageLayout layout = layout();
        AXMemorySystem memorySystem = memorySystem(layout);
        AXScope scope = scope();
        AXAttachedWorldEvent first = new AXAttachedWorldEvent(
                "",
                "player_death",
                "death:bucket",
                scope.worldId(),
                "minecraft:overworld",
                "10,64,20",
                1000L,
                "",
                "玩家在主世界死亡一次。",
                List.of("minecraft:death")
        );
        AXAttachedWorldEvent duplicate = new AXAttachedWorldEvent(
                "",
                "player_death",
                "death:bucket",
                scope.worldId(),
                "minecraft:overworld",
                "10,64,20",
                1001L,
                "",
                "玩家在主世界死亡一次。",
                List.of("minecraft:death")
        );

        memorySystem.ensureStorageManifest(scope);
        memorySystem.attachedWorldEvents().appendAll(scope, List.of(first, duplicate));

        assertEquals(1, memorySystem.attachedWorldEvents().loadAll(scope).size());
        assertTrue(Files.isRegularFile(layout.attachedWorldEventsFile(scope)));
    }

    @Test
    void worldManifestIsNotRewrittenAfterItIsAlreadyComplete() throws Exception {
        AXStorageLayout layout = layout();
        AXMemorySystem memorySystem = memorySystem(layout);
        AXScope scope = scope();

        memorySystem.appendStmBlock(scope, new AXStmBlock("", "", scope.worldId(), 1000L, 900L, 950L, "", "", 1, 0, "第一段 STM。", List.of()));
        String manifestBefore = Files.readString(layout.worldManifestFile(scope), StandardCharsets.UTF_8);
        Thread.sleep(2L);
        memorySystem.appendStmBlock(scope, new AXStmBlock("", "", scope.worldId(), 2000L, 1900L, 1950L, "", "", 1, 0, "第二段 STM。", List.of()));
        String manifestAfter = Files.readString(layout.worldManifestFile(scope), StandardCharsets.UTF_8);

        assertEquals(manifestBefore, manifestAfter);
    }

    @Test
    void rawTurnsStayInRollingRuntimeWindowUntilCompressionPipelinePersistsStm() {
        AXStorageLayout layout = layout();
        AXMemorySystem memorySystem = memorySystem(layout);
        AXScope scope = scope();

        memorySystem.appendRawTurn(scope, AXRawTurn.dialogue(scope, "user", "第一句话", "session", "turn-1"));
        memorySystem.appendRawTurn(scope, AXRawTurn.dialogue(scope, "assistant", "第一句回答", "session", "turn-1"));

        AXMemorySnapshot snapshot = memorySystem.load(scope);

        assertEquals(2, snapshot.recentDialogueTurns().size());
        assertTrue(snapshot.recentDialogueTurns().get(1).assistantRole());
        assertFalse(Files.exists(layout.rawTurnsRoot(scope).resolve("raw_turns.jsonl")));
    }

    @Test
    void compressionBatchIsConsumedOnlyAfterExplicitConfirmation() {
        AXStorageLayout layout = layout();
        AXMemorySystem memorySystem = memorySystem(layout);
        AXScope scope = scope();

        memorySystem.appendRawTurn(scope, AXRawTurn.dialogue(scope, "user", "第一句很长的玩家输入，用来触发压缩窗口。", "session", "turn-1"));
        memorySystem.appendRawTurn(scope, AXRawTurn.dialogue(scope, "assistant", "第一句很长的回答，用来触发压缩窗口。", "session", "turn-1"));
        memorySystem.appendRawTurn(scope, AXRawTurn.dialogue(scope, "user", "第二句保留在窗口里。", "session", "turn-2"));

        AXRawTurnBatch batch = memorySystem.selectCompressionBatch(scope);

        assertFalse(batch.isEmpty());
        assertEquals(3, memorySystem.load(scope).recentDialogueTurns().size());
        assertEquals(batch.turns().size(), memorySystem.confirmRawTurnsConsumed(scope, batch));
        assertEquals(3 - batch.turns().size(), memorySystem.load(scope).recentDialogueTurns().size());
    }

    private AXMemorySystem memorySystem(AXStorageLayout layout) {
        AXMemoryWindowPolicy policy = new AXMemoryWindowPolicy(
                8000,
                4000,
                1500,
                1000,
                500,
                500,
                25,
                40,
                25,
                40,
                28000,
                120000,
                3,
                60000L
        );
        return new AXMemorySystem(layout, new AXJsonStore(new TestLlmSupport.FakeGameEnvironment()), policy);
    }

    private AXStorageLayout layout() {
        return new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
    }

    private AXScope scope() {
        return new AXScope("player", "save:Test World", "Test World", AXScopeKind.LOCAL_WORLD, true);
    }
}
