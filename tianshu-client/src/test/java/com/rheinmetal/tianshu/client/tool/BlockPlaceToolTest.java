package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.BlockPlacePort;
import com.rheinmetal.tianshu.api.game.BlockPlaceResult;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockPlaceToolTest {

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
        TianshuTools.unregister(BlockPlaceTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
        AgentBodies.clearForTests();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(BlockPlaceTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    private static void enable() {
        TianshuTools.register(new BlockPlaceTool());
        TianshuTools.setEnabled(BlockPlaceTool.ID, true);
    }

    @Test
    void aBodyWithoutPlaceIsRefusedBeforeThePortIsTouched() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        TianshuRuntimePorts.register(BlockPlacePort.class, (actor, request) -> {
            reached.set(true);
            return CompletableFuture.completedFuture(
                    new BlockPlaceResult("minecraft:stone", 0, 0, 0, true, "placed"));
        });
        enable();
        TestBodies.register("float", BodyCapability.MOVE, BodyCapability.LOOK);

        JsonObject refused = envelope("{\"item\":\"minecraft:stone\",\"x\":0,\"y\":64,\"z\":0}",
                ToolCaller.body("float", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNSUPPORTED_ACTOR", refused.get("code").getAsString());
        assertFalse(reached.get(), "capability gating must run before the port and the action player");
    }

    @Test
    void aBodyWithPlaceReachesThePortAsABodyActor() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        TianshuRuntimePorts.register(BlockPlacePort.class, (actor, request) -> {
            seen.set(actor);
            return CompletableFuture.completedFuture(
                    new BlockPlaceResult("minecraft:stone", 1, 64, 2, true, "placed"));
        });
        enable();
        TestBodies.register("body-1", BodyCapability.PLACE);

        JsonObject result = envelope("{\"item\":\"minecraft:stone\",\"x\":1,\"y\":64,\"z\":2}",
                ToolCaller.body("body-1", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.BODY, seen.get().kind());
        assertEquals("body-1", seen.get().id());
    }

    @Test
    void aPlayerMayDriveABody() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        TianshuRuntimePorts.register(BlockPlacePort.class, (actor, request) -> {
            seen.set(actor);
            return CompletableFuture.completedFuture(
                    new BlockPlaceResult("minecraft:stone", 0, 0, 0, true, "placed"));
        });
        enable();
        TestBodies.register("body-1", BodyCapability.PLACE);

        JsonObject result = envelope(
                "{\"body\":\"body-1\",\"item\":\"minecraft:stone\",\"x\":0,\"y\":64,\"z\":0}",
                ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.BODY, seen.get().kind());
    }

    @Test
    void aPlayerWithoutABodyStaysTheActor() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        TianshuRuntimePorts.register(BlockPlacePort.class, (actor, request) -> {
            seen.set(actor);
            return CompletableFuture.completedFuture(
                    new BlockPlaceResult("minecraft:stone", 0, 0, 0, true, "placed"));
        });
        enable();

        JsonObject result = envelope("{\"item\":\"minecraft:stone\",\"x\":0,\"y\":64,\"z\":0}",
                ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.PLAYER, seen.get().kind());
        assertEquals("p", seen.get().id());
    }

    @Test
    void anUnknownBodyIsUnavailable() throws Exception {
        enable();

        JsonObject refused = envelope(
                "{\"body\":\"gone\",\"item\":\"minecraft:stone\",\"x\":0,\"y\":64,\"z\":0}",
                ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", refused.get("code").getAsString());
    }
}