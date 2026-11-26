package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.BlockPlacePort;
import com.rheinmetal.tianshu.api.game.BlockPlaceResult;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolId;
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

/**
 * The split pair: {@code block.place} serves a player, {@code entity.place} serves a body, and neither serves the other's
 * executor. The port sees the actor the pair picked, which is the whole point of having two ids.
 */
class BlockPlaceToolTest {

    private static final String STONE = "{\"item\":\"minecraft:stone\",\"x\":0,\"y\":64,\"z\":0}";

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
        TianshuTools.unregister(BodyPlaceTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
        EntityActors.clearForTests();
    }

    private static JsonObject envelope(ToolId id, String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(id, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    private static void enable() {
        TianshuTools.register(new BlockPlaceTool());
        TianshuTools.register(new BodyPlaceTool());
        TianshuTools.setEnabled(BlockPlaceTool.ID, true);
        TianshuTools.setEnabled(BodyPlaceTool.ID, true);
    }

    private static void port(AtomicReference<ActorRef> seen, AtomicBoolean reached) {
        TianshuRuntimePorts.register(BlockPlacePort.class, (actor, request) -> {
            if (reached != null) {
                reached.set(true);
            }
            if (seen != null) {
                seen.set(actor);
            }
            return CompletableFuture.completedFuture(
                    new BlockPlaceResult("minecraft:stone", 0, 64, 0, true, "placed"));
        });
    }

    @Test
    void aPlayerPlacesAsThePlayer() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        port(seen, null);
        enable();

        JsonObject result = envelope(BlockPlaceTool.ID, STONE, ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.PLAYER, seen.get().kind());
        assertEquals("p", seen.get().id());
    }

    @Test
    void aBodyCallerIsSentToTheEntitySide() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        port(null, reached);
        enable();
        TestBodies.register("body-1", EntityCapability.PLACE);

        JsonObject refused = envelope(BlockPlaceTool.ID, STONE, ToolCaller.body("body-1", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNSUPPORTED_ACTOR", refused.get("code").getAsString());
        assertFalse(reached.get(), "the player-side tool must not reach the world for a body");
    }

    @Test
    void anAgentIsSentToTheEntitySide() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        port(null, reached);
        enable();

        JsonObject refused = envelope(BlockPlaceTool.ID, STONE, ToolCaller.agent(false));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNSUPPORTED_ACTOR", refused.get("code").getAsString());
        assertFalse(reached.get());
    }

    @Test
    void aBodyPlacesThroughTheEntitySide() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        port(seen, null);
        enable();
        TestBodies.register("body-1", EntityCapability.PLACE);

        JsonObject result = envelope(BodyPlaceTool.ID, STONE, ToolCaller.body("body-1", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.BODY, seen.get().kind());
        assertEquals("body-1", seen.get().id());
    }

    @Test
    void aPlayerMayDriveABodyThroughTheEntitySide() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        port(seen, null);
        enable();
        TestBodies.register("body-1", EntityCapability.PLACE);

        JsonObject result = envelope(BodyPlaceTool.ID,
                "{\"body\":\"body-1\",\"item\":\"minecraft:stone\",\"x\":0,\"y\":64,\"z\":0}",
                ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.BODY, seen.get().kind());
    }

    @Test
    void theEntitySideNeedsABodyFromAPlayer() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        port(null, reached);
        enable();

        JsonObject refused = envelope(BodyPlaceTool.ID, STONE, ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("INVALID_ARGS", refused.get("code").getAsString());
        assertEquals("body.target.required", refused.get("message").getAsString());
        assertFalse(reached.get());
    }

    @Test
    void anUnknownBodyIsUnavailable() throws Exception {
        enable();

        JsonObject refused = envelope(BodyPlaceTool.ID,
                "{\"body\":\"gone\",\"item\":\"minecraft:stone\",\"x\":0,\"y\":64,\"z\":0}",
                ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", refused.get("code").getAsString());
    }
}