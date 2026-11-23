package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
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
    void clearThrottle() {
        TianshuTools.clearRateLimits();
    }

    @BeforeEach
    void declare() {
        ClientTools.register();
    }

    @AfterEach
    void clean() {
        TianshuTools.unregister(ContainerTransferTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
        EntityActors.clearForTests();
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
    void anAgentMayNotTransfer() throws Exception {
        TianshuTools.register(new ContainerTransferTool());
        TianshuTools.setEnabled(ContainerTransferTool.ID, true);
        JsonObject denied = envelope(REQUEST, ToolCaller.agent(false));
        assertFalse(denied.get("ok").getAsBoolean());
        // An agent has no actor to act as; that is a parameter problem, not a missing permission.
        assertEquals("INVALID_ARGS", denied.get("code").getAsString());
    }

    @Test
    void aBodyWithoutAnInventoryIsRefusedAsUnsupported() throws Exception {
        TianshuTools.register(new ContainerTransferTool());
        TianshuTools.setEnabled(ContainerTransferTool.ID, true);
        TestBodies.register("float", EntityCapability.MOVE, EntityCapability.LOOK);

        JsonObject refused = envelope(REQUEST, ToolCaller.body("float", true));
        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNSUPPORTED_ACTOR", refused.get("code").getAsString());
    }

    @Test
    void slotsAndCountsAreCheckedBeforeTheGameSeesThem() throws Exception {
        TianshuTools.register(new ContainerTransferTool());
        TianshuTools.setEnabled(ContainerTransferTool.ID, true);

        String badSlot = "{\"x\":1,\"y\":2,\"z\":3,\"containerSlot\":-1,\"playerSlot\":0,\"count\":1,\"toPlayer\":true}";
        assertEquals("INVALID_ARGS", envelope(badSlot, ToolCaller.player("v1", true)).get("code").getAsString());

        String badPlayerSlot = "{\"x\":1,\"y\":2,\"z\":3,\"containerSlot\":0,\"playerSlot\":40,\"count\":1,\"toPlayer\":true}";
        assertEquals("INVALID_ARGS", envelope(badPlayerSlot, ToolCaller.player("v2", true)).get("code").getAsString());

        String badCount = "{\"x\":1,\"y\":2,\"z\":3,\"containerSlot\":0,\"playerSlot\":0,\"count\":0,\"toPlayer\":true}";
        assertEquals("INVALID_ARGS", envelope(badCount, ToolCaller.player("v3", true)).get("code").getAsString());
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