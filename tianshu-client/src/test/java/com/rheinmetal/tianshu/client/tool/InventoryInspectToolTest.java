package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.InventoryEntry;
import com.rheinmetal.tianshu.api.game.InventoryQueryPort;
import com.rheinmetal.tianshu.api.game.InventorySnapshot;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryInspectToolTest {

    @BeforeEach
    void registerTools() {
        ClientTools.register();
    }

    @AfterEach
    void clean() {
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
    }

    @Test
    void returnsTheOccupiedSlots() throws Exception {
        TianshuRuntimePorts.register(InventoryQueryPort.class, () -> CompletableFuture.completedFuture(
                new InventorySnapshot(List.of(
                        new InventoryEntry(0, "minecraft:iron_ingot", 12),
                        new InventoryEntry(9, "minecraft:bread", 3)))));

        JsonObject envelope = TianshuTools.callJson(InventoryInspectTool.ID, JsonParser.parseString("{}"),
                ToolCaller.agent(false)).get(2, TimeUnit.SECONDS);
        assertTrue(envelope.get("ok").getAsBoolean());
        assertEquals(2, envelope.getAsJsonObject("result").getAsJsonArray("slots").size());
    }

    @Test
    void aTypedHandleReturnsTheSameSnapshot() throws Exception {
        TianshuRuntimePorts.register(InventoryQueryPort.class, () -> CompletableFuture.completedFuture(
                new InventorySnapshot(List.of(new InventoryEntry(3, "minecraft:stick", 64)))));

        ToolHandle<EmptyArgs, InventorySnapshot> handle =
                TianshuTools.handle(InventoryInspectTool.ID, EmptyArgs.class, InventorySnapshot.class);
        InventorySnapshot snapshot = handle.call(new EmptyArgs(), ToolCaller.mod("m")).get(2, TimeUnit.SECONDS);
        assertEquals(1, snapshot.slots().size());
        assertEquals("minecraft:stick", snapshot.slots().get(0).itemId());
    }

    @Test
    void withoutAPortTheToolReportsUnavailable() throws Exception {
        JsonObject envelope = TianshuTools.callJson(InventoryInspectTool.ID, JsonParser.parseString("{}"),
                ToolCaller.agent(false)).get(2, TimeUnit.SECONDS);
        assertFalse(envelope.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", envelope.get("code").getAsString());
    }

    @Test
    void theToolIsAdvertisedAsALocalQuery() {
        assertTrue(TianshuTools.catalog(ToolCaller.agent(false)).stream()
                .anyMatch(spec -> spec.id().equals(InventoryInspectTool.ID)
                        && spec.enabled()
                        && "LOCAL".equals(spec.category().name())));
    }
}