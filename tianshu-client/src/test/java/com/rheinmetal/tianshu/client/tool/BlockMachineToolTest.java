package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.BlockMachinePort;
import com.rheinmetal.tianshu.api.game.MachineOpResult;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockMachineToolTest {

    @BeforeEach
    void declare() {
        ClientTools.register();
    }

    @AfterEach
    void clean() {
        TianshuTools.unregister(BlockMachineTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(BlockMachineTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    private static final String FUEL =
            "{\"x\":1,\"y\":2,\"z\":3,\"operation\":\"insert_fuel\",\"playerSlot\":4}";

    @Test
    void machineOpsAreOffUntilEnabled() throws Exception {
        TianshuRuntimePorts.register(BlockMachinePort.class, (playerId, request) -> CompletableFuture.completedFuture(
                new MachineOpResult("minecraft:furnace", request.operation(), "minecraft:coal", 1, "inserted")));
        TianshuTools.register(new BlockMachineTool());

        assertEquals("DISABLED", envelope(FUEL, ToolCaller.player("p", true)).get("code").getAsString());

        TianshuTools.setEnabled(BlockMachineTool.ID, true);
        JsonObject result = envelope(FUEL, ToolCaller.player("p", true));
        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(1, result.getAsJsonObject("result").get("moved").getAsInt());
    }

    @Test
    void onlyAPlayerMayOperateAMachine() throws Exception {
        TianshuTools.register(new BlockMachineTool());
        TianshuTools.setEnabled(BlockMachineTool.ID, true);
        JsonObject denied = envelope(FUEL, ToolCaller.agent(false));
        assertEquals("NO_PERMISSION", denied.get("code").getAsString());
    }

    @Test
    void operationsAndSlotsAreCheckedBeforeTheGameSeesThem() throws Exception {
        TianshuTools.register(new BlockMachineTool());
        TianshuTools.setEnabled(BlockMachineTool.ID, true);

        String badOp = "{\"x\":1,\"y\":2,\"z\":3,\"operation\":\"explode\"}";
        assertEquals("INVALID_ARGS", envelope(badOp, ToolCaller.player("p", true)).get("code").getAsString());

        String badSlot = "{\"x\":1,\"y\":2,\"z\":3,\"operation\":\"insert_fuel\",\"playerSlot\":99}";
        assertEquals("INVALID_ARGS", envelope(badSlot, ToolCaller.player("p", true)).get("code").getAsString());
    }

    @Test
    void takeOutputDoesNotNeedAPlayerSlot() throws Exception {
        TianshuRuntimePorts.register(BlockMachinePort.class, (playerId, request) -> CompletableFuture.completedFuture(
                new MachineOpResult("minecraft:furnace", request.operation(), "minecraft:iron_ingot", 3, "taken")));
        TianshuTools.register(new BlockMachineTool());
        TianshuTools.setEnabled(BlockMachineTool.ID, true);

        JsonObject result = envelope("{\"x\":1,\"y\":2,\"z\":3,\"operation\":\"take_output\"}",
                ToolCaller.player("p", true));
        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(3, result.getAsJsonObject("result").get("moved").getAsInt());
    }

    @Test
    void withoutAPortTheToolReportsUnavailable() throws Exception {
        TianshuTools.register(new BlockMachineTool());
        TianshuTools.setEnabled(BlockMachineTool.ID, true);
        JsonObject result = envelope(FUEL, ToolCaller.player("p", true));
        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", result.get("code").getAsString());
    }
}