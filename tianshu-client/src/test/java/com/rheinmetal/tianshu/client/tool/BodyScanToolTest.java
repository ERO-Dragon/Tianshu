package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentWorldQueryPort;
import com.rheinmetal.tianshu.api.agent.ScanResult;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.InventorySnapshot;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyScanToolTest {

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
        TianshuTools.unregister(BodyScanTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
        AgentBodies.clearForTests();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(BodyScanTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    private static void enable() {
        TianshuTools.register(new BodyScanTool());
    }

    @Test
    void aScanReachesThePortAsTheNamedBody() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        TianshuRuntimePorts.register(AgentWorldQueryPort.class, new AgentWorldQueryPort() {
            @Override
            public CompletableFuture<ScanResult> scan(ActorRef actor, com.rheinmetal.tianshu.api.agent.ScanRequest request) {
                seen.set(actor);
                return CompletableFuture.completedFuture(new ScanResult(List.of(), List.of()));
            }

            @Override
            public CompletableFuture<InventorySnapshot> inventory(ActorRef actor) {
                throw new AssertionError("scan must not read the inventory");
            }
        });
        enable();
        TestBodies.register("body-1");

        JsonObject result = envelope("{\"body\":\"body-1\",\"blocks\":\"minecraft:stone\"}",
                ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.BODY, seen.get().kind());
        assertEquals("body-1", seen.get().id());
    }

    @Test
    void aPlayerWithoutABodyScansAroundThePlayer() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        TianshuRuntimePorts.register(AgentWorldQueryPort.class, new AgentWorldQueryPort() {
            @Override
            public CompletableFuture<ScanResult> scan(ActorRef actor, com.rheinmetal.tianshu.api.agent.ScanRequest request) {
                seen.set(actor);
                return CompletableFuture.completedFuture(new ScanResult(List.of(), List.of()));
            }

            @Override
            public CompletableFuture<InventorySnapshot> inventory(ActorRef actor) {
                return CompletableFuture.completedFuture(new InventorySnapshot(List.of()));
            }
        });
        enable();

        JsonObject result = envelope("{\"entities\":\"minecraft:zombie\"}", ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.PLAYER, seen.get().kind());
    }

    @Test
    void aScanWithoutAFilterIsInvalid() throws Exception {
        enable();

        JsonObject refused = envelope("{\"body\":\"body-1\"}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("INVALID_ARGS", refused.get("code").getAsString());
    }

    @Test
    void anUnknownBodyIsUnavailable() throws Exception {
        enable();

        JsonObject refused = envelope("{\"body\":\"gone\",\"blocks\":\"minecraft:stone\"}",
                ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", refused.get("code").getAsString());
    }
}