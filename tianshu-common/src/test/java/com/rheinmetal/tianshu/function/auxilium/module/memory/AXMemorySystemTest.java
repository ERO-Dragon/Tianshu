package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSystem;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.index.AXMemoryRetrievalIndex;
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
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXAttachedWorldEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXEventVector;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance.AXMemoryDerivedMaintenanceResult;
import com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance.AXMemoryDerivedMaintenanceService;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurnBatch;

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
        assertFalse(manifest.getAsJsonObject("derivedArtifacts").get("authority").getAsBoolean());
        assertTrue(manifest.getAsJsonObject("derivedArtifacts").getAsJsonArray("rebuildable").contains(JsonParser.parseString("\"l1Clusters\"")));

        AXMemoryStorageCompatibilityReport report = memorySystem.checkStorageCompatibility(scope);
        assertTrue(report.compatible());
        assertFalse(report.hasErrors());
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
    void storageCompatibilityFlagsFutureSchemaVersions() throws Exception {
        AXStorageLayout layout = layout();
        AXMemorySystem memorySystem = memorySystem(layout);
        AXScope scope = scope();

        memorySystem.ensureStorageManifest(scope);
        JsonObject manifest = JsonParser.parseString(Files.readString(layout.worldManifestFile(scope), StandardCharsets.UTF_8)).getAsJsonObject();
        manifest.addProperty("layoutVersion", AXMemoryStorageManifestStore.LAYOUT_VERSION + 1);
        Files.writeString(layout.worldManifestFile(scope), manifest.toString(), StandardCharsets.UTF_8);

        AXMemoryStorageCompatibilityReport report = memorySystem.checkStorageCompatibility(scope);

        assertFalse(report.compatible());
        assertTrue(report.errorCodes().contains("AX_MEMORY_LAYOUT_VERSION_FUTURE"));
    }

    @Test
    void storageCompatibilityFlagsFutureEntitySchemaVersions() throws Exception {
        AXStorageLayout layout = layout();
        AXMemorySystem memorySystem = memorySystem(layout);
        AXScope scope = scope();

        memorySystem.ensureStorageManifest(scope);
        JsonObject manifest = JsonParser.parseString(Files.readString(layout.worldManifestFile(scope), StandardCharsets.UTF_8)).getAsJsonObject();
        manifest.getAsJsonObject("schemas").addProperty("memoryEvent", AXMemoryEvent.SCHEMA_VERSION + 1);
        Files.writeString(layout.worldManifestFile(scope), manifest.toString(), StandardCharsets.UTF_8);

        AXMemoryStorageCompatibilityReport report = memorySystem.checkStorageCompatibility(scope);

        assertFalse(report.compatible());
        assertTrue(report.errorCodes().contains("AX_MEMORY_SCHEMA_FUTURE_memoryEvent"));
    }

    @Test
    void durableWritesAreSkippedWhenManifestIsFromFutureLayout() throws Exception {
        AXStorageLayout layout = layout();
        AXMemorySystem memorySystem = memorySystem(layout);
        AXScope scope = scope();

        memorySystem.ensureStorageManifest(scope);
        JsonObject manifest = JsonParser.parseString(Files.readString(layout.worldManifestFile(scope), StandardCharsets.UTF_8)).getAsJsonObject();
        manifest.addProperty("layoutVersion", AXMemoryStorageManifestStore.LAYOUT_VERSION + 1);
        Files.writeString(layout.worldManifestFile(scope), manifest.toString(), StandardCharsets.UTF_8);

        memorySystem.appendStmBlock(scope, new AXStmBlock("", "", scope.worldId(), 1000L, 900L, 950L, "", "", 1, 0, "不会写入的 STM。", List.of()));

        assertFalse(Files.exists(layout.stmBlocksFile(scope)));
    }

    @Test
    void durableWritesAreSkippedWhenManifestHasFutureEntitySchema() throws Exception {
        AXStorageLayout layout = layout();
        AXMemorySystem memorySystem = memorySystem(layout);
        AXScope scope = scope();

        memorySystem.ensureStorageManifest(scope);
        JsonObject manifest = JsonParser.parseString(Files.readString(layout.worldManifestFile(scope), StandardCharsets.UTF_8)).getAsJsonObject();
        manifest.getAsJsonObject("schemas").addProperty("memoryEvent", AXMemoryEvent.SCHEMA_VERSION + 1);
        Files.writeString(layout.worldManifestFile(scope), manifest.toString(), StandardCharsets.UTF_8);

        memorySystem.appendMemoryEvent(scope, new AXMemoryEvent("", "不会写入的 E。", "", "stm_block", "stm_fact", scope.worldId(), "", "", false, 1000L, 1000L, 0, List.of()));

        assertFalse(Files.exists(layout.eventsFile(scope)));
    }

    @Test
    void eventVectorsAreIsolatedByEmbeddingNamespace() {
        AXStorageLayout layout = layout();
        AXMemorySystem memorySystem = memorySystem(layout);
        AXScope scope = scope();
        AXStmBlock block = new AXStmBlock("", "", scope.worldId(), 1000L, 900L, 950L, "", "", 1, 0, "玩家把钻石镐放进末影箱。", List.of());
        AXMemoryEvent event = new AXMemoryEvent("", "玩家把钻石镐放进末影箱。", "", block.id(), "stm_fact", scope.worldId(), "", "", false, 1000L, 1000L, 0, List.of("minecraft:diamond_pickaxe"));

        memorySystem.appendStmBlock(scope, block);
        memorySystem.appendMemoryEvent(scope, event);
        memorySystem.appendEventVector(scope, new AXEventVector(event.id(), event.factHash(), "embed-a", "embed:a", 2, new float[]{1.0F, 0.0F}, 1000L));

        AXMemoryRetrievalIndex matching = memorySystem.retrievalIndex(scope, "embed:a");
        AXMemoryRetrievalIndex otherNamespace = memorySystem.retrievalIndex(scope, "embed:b");

        assertFalse(matching.isEmpty());
        assertTrue(otherNamespace.isEmpty());
        assertEquals(1, memorySystem.vectors().load(scope, "embed:a").size());
        assertEquals(0, memorySystem.vectors().load(scope, "embed:b").size());
        assertEquals(1, memorySystem.vectors().loadAllNamespaces(scope).size());
    }

    @Test
    void rawTurnsStayInRollingRuntimeWindowUntilCompressionPipelinePersistsStm() {
        AXStorageLayout layout = layout();
        AXMemorySystem memorySystem = memorySystem(layout);
        AXRecentDialogueSystem recentDialogueSystem = recentDialogueSystem();
        AXScope scope = scope();

        recentDialogueSystem.append(scope, AXRawTurn.dialogue(scope, "user", "第一句话", "session", "turn-1"));
        recentDialogueSystem.append(scope, AXRawTurn.dialogue(scope, "assistant", "第一句回答", "session", "turn-1"));

        AXMemorySnapshot snapshot = memorySystem.load(scope);
        AXRecentDialogueSnapshot recentSnapshot = recentDialogueSystem.snapshot(scope);

        assertTrue(snapshot.recentPlayerMemoryBlocks().isEmpty());
        assertEquals(2, recentSnapshot.turns().size());
        assertTrue(recentSnapshot.turns().get(1).assistantRole());
    }

    @Test
    void compressionBatchIsConsumedOnlyAfterExplicitConfirmation() {
        AXStorageLayout layout = layout();
        AXMemorySystem memorySystem = memorySystem(layout);
        AXRecentDialogueSystem recentDialogueSystem = recentDialogueSystem();
        AXScope scope = scope();

        recentDialogueSystem.append(scope, AXRawTurn.dialogue(scope, "user", "第一句很长的玩家输入，用来触发压缩窗口。", "session", "turn-1"));
        recentDialogueSystem.append(scope, AXRawTurn.dialogue(scope, "assistant", "第一句很长的回答，用来触发压缩窗口。", "session", "turn-1"));
        recentDialogueSystem.append(scope, AXRawTurn.dialogue(scope, "user", "第二句保留在窗口里。", "session", "turn-2"));

        AXRawTurnBatch batch = recentDialogueSystem.selectCompressionBatch(scope);

        assertFalse(batch.isEmpty());
        assertEquals(3, recentDialogueSystem.snapshot(scope).turns().size());
        assertEquals(batch.turns().size(), recentDialogueSystem.confirmConsumed(scope, batch));
        assertEquals(3 - batch.turns().size(), recentDialogueSystem.snapshot(scope).turns().size());
    }

    @Test
    void derivedMaintenanceRebuildsStatsAndNormalizesStmChainWithoutDroppingEvents() {
        AXStorageLayout layout = layout();
        AXMemorySystem memorySystem = memorySystem(layout);
        AXScope scope = scope();
        AXStmBlock s1 = new AXStmBlock("stm_s1", "", scope.worldId(), 1000L, 900L, 950L, "", "", 1, 0, "第一段。", List.of());
        AXStmBlock s2 = new AXStmBlock("stm_s2", "", scope.worldId(), 2000L, 1900L, 1950L, "", "", 1, 0, "第二段。", List.of());
        memorySystem.stmBlocks().rewrite(scope, List.of(s1, s2));
        AXMemoryEvent event = new AXMemoryEvent("", "玩家做了一件事。", "", s2.id(), "stm_fact", scope.worldId(), "", "", false, 2100L, 1950L, 0, List.of());
        memorySystem.events().appendAll(scope, List.of(event));
        memorySystem.vectors().appendAll(scope, List.of(new AXEventVector(event.id(), event.factHash(), "embed", "embed:v1", 2, new float[]{1.0F, 0.0F}, 2200L)));

        AXMemoryDerivedMaintenanceResult result = new AXMemoryDerivedMaintenanceService(memorySystem).maintain(scope);

        assertTrue(result.ran());
        assertTrue(result.stmChainRewritten());
        assertEquals(1, memorySystem.events().loadAll(scope).size());
        assertEquals("stm_s1", memorySystem.stmBlocks().loadAll(scope).get(1).previousStmId());
        assertEquals("stm_s2", memorySystem.stmBlocks().loadAll(scope).get(0).nextStmId());
        AXMemoryStatsSnapshot stats = memorySystem.stats().load(scope);
        assertEquals(2, stats.stmBlockCount());
        assertEquals(1, stats.memoryEventCount());
        assertEquals(1, stats.vectorCount());
        assertTrue(Files.isRegularFile(layout.memoryStatsFile(scope)));
    }

    private AXMemorySystem memorySystem(AXStorageLayout layout) {
        AXMemoryWindowPolicy policy = AXMemoryWindowPolicy.fromBudget(8000, 3, 60000L);
        return new AXMemorySystem(layout, new AXJsonStore(new TestLlmSupport.FakeGameEnvironment()), policy);
    }

    private AXRecentDialogueSystem recentDialogueSystem() {
        AXMemoryWindowPolicy policy = new AXMemoryWindowPolicy(
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
                10,
                1000,
                10,
                1000,
                10000,
                10000,
                3,
                60000L
        );
        return new AXRecentDialogueSystem(policy, (requestId, role, content) -> java.util.OptionalInt.of(4));
    }

    private AXStorageLayout layout() {
        return new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
    }

    private AXScope scope() {
        return new AXScope("player", "save:Test World", "Test World", AXScopeKind.LOCAL_WORLD, true);
    }
}
