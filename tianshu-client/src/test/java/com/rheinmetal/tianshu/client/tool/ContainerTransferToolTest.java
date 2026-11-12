package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.ContainerTransferResult;
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

class ContainerTransferToolTest {

    @BeforeEach
    void declare() {
        ClientTools.register();
    }

    @AfterEach
    void clean() {
        TianshuTools.unregister(ContainerTransferTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(ContainerTransferTool.ID, JsonParser.parseString(json), caller,
                        ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    private static final String REQUEST =
            "{\"x\":1,\"y\":2,\"z\":3,\"containerSlot\":0,\"playerSlot\":0,\"count\":8,\"toPlayer\":true}";

    @Test
    void transferIsOffUntilEnabledAndThenMoves() throws Exception {
        TianshuRuntimePorts.register(ContainerTransferPort.class, (playerId, request) ->
                CompletableFuture.completedFuture(new ContainerTransferResult("minecraft:iron_ingot", 8, "moved")));
        TianshuTools.register(new ContainerTransferTool());

        JsonObject disabled = envelope(REQUEST, ToolCaller.player("p", true));
        assertEquals("DISABLED", disabled.get("code").getAsString());

        TianshuTools.setEnabled(ContainerTransferTool.ID, true);
        JsonObject moved = envelope(REQUEST, ToolCaller.player("p", true));
        assertTrue(moved.get("ok").getAsBoolean());
        assertEquals(8, moved.getAsJsonObject("result").get("moved").getAsInt());
    }

    @Test
    void onlyAPlayerMayTransfer() throws Exception {
        TianshuTools.register(new ContainerTransferTool());
        TianshuTools.setEnabled(ContainerTransferTool.ID, true);
        JsonObject denied = envelope(REQUEST, ToolCaller.agent(false));
        assertFalse(denied.get("ok").getAsBoolean());
        assertEquals("NO_PERMISSION", denied.get("code").getAsString());
    }

    @Test
    void slotsAndCountsAreCheckedBeforeTheGameSeesThem() throws Exception {
        TianshuTools.register(new ContainerTransferTool());
        TianshuTools.setEnabled(ContainerTransferTool.ID, true);

        String badSlot = "{\"x\":1,\"y\":2,\"z\":3,\"containerSlot\":-1,\"playerSlot\":0,\"count\":1,\"toPlayer\":true}";
        assertEquals("INVALID_ARGS", envelope(badSlot, ToolCaller.player("p", true)).get("code").getAsString());

        String badPlayerSlot = "{\"x\":1,\"y\":2,\"z\":3,\"containerSlot\":0,\"playerSlot\":40,\"count\":1,\"toPlayer\":true}";
        assertEquals("INVALID_ARGS", envelope(badPlayerSlot, ToolCaller.player("p", true)).get("code").getAsString());

        String badCount = "{\"x\":1,\"y\":2,\"z\":3,\"containerSlot\":0,\"playerSlot\":0,\"count\":0,\"toPlayer\":true}";
        assertEquals("INVALID_ARGS", envelope(badCount, ToolCaller.player("p", true)).get("code").getAsString());
    }

    @Test
    void withoutAPortTheToolReportsUnavailable() throws Exception {
        TianshuTools.register(new ContainerTransferTool());
        TianshuTools.setEnabled(ContainerTransferTool.ID, true);
        JsonObject result = envelope(REQUEST, ToolCaller.player("p", true));
        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", result.get("code").getAsString());
    }
}