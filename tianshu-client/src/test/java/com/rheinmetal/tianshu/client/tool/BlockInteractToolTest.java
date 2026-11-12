package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.BlockInteractResult;
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

class BlockInteractToolTest {

    @BeforeEach
    void registerTool() {
        ClientTools.register();
    }

    @AfterEach
    void clean() {
        TianshuTools.unregister(BlockInteractTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(BlockInteractTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    @Test
    void aWriteToolIsOffUntilAnOperatorEnablesIt() throws Exception {
        TianshuRuntimePorts.register(BlockInteractPort.class, (playerId, request) -> CompletableFuture.completedFuture(
                new BlockInteractResult("minecraft:oak_door", true, "operated")));
        TianshuTools.register(new BlockInteractTool());

        JsonObject disabled = envelope("{\"x\":1,\"y\":2,\"z\":3}", ToolCaller.player("p", true));
        assertFalse(disabled.get("ok").getAsBoolean());
        assertEquals("DISABLED", disabled.get("code").getAsString());

        TianshuTools.setEnabled(BlockInteractTool.ID, true);
        JsonObject allowed = envelope("{\"x\":1,\"y\":2,\"z\":3}", ToolCaller.player("p", true));
        assertTrue(allowed.get("ok").getAsBoolean());
        assertEquals("minecraft:oak_door",
                allowed.getAsJsonObject("result").get("blockId").getAsString());
    }

    @Test
    void onlyAPlayerMayOperateABlock() throws Exception {
        TianshuTools.register(new BlockInteractTool());
        TianshuTools.setEnabled(BlockInteractTool.ID, true);

        JsonObject denied = envelope("{\"x\":1,\"y\":2,\"z\":3}", ToolCaller.agent(false));
        assertFalse(denied.get("ok").getAsBoolean());
        assertEquals("NO_PERMISSION", denied.get("code").getAsString());
    }

    @Test
    void withoutAPortTheToolReportsUnavailable() throws Exception {
        TianshuTools.register(new BlockInteractTool());
        TianshuTools.setEnabled(BlockInteractTool.ID, true);

        JsonObject result = envelope("{\"x\":1,\"y\":2,\"z\":3}", ToolCaller.player("p", true));
        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", result.get("code").getAsString());
    }

    @Test
    void theDeclarationShipsWithTheClient() {
        assertTrue(TianshuTools.catalog(ToolCaller.agent(false)).stream()
                .noneMatch(spec -> spec.id().equals(BlockInteractTool.ID)),
                "a server tool is not advertised before its catalog arrives");
    }
}