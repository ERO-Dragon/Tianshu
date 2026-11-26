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
 * {@code entity.place} acts as a body and only as a body: the port sees a BODY actor, and a caller that names no body is
 * refused instead of silently placing as itself.
 */
class BodyPlaceToolTest {

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
        TianshuTools.unregister(BodyPlaceTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
        EntityActors.clearForTests();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(BodyPlaceTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    private static void enable() {
        TianshuTools.register(new BodyPlaceTool());
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
    void aBodyPlacesAsItself() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        port(seen, null);
        enable();
        TestBodies.register("body-1", EntityCapability.PLACE);

        JsonObject result = envelope(STONE, ToolCaller.body("body-1", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.BODY, seen.get().kind());
        assertEquals("body-1", seen.get().id());
    }

    @Test
    void aPlayerMayDriveABody() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        port(seen, null);
        enable();
        TestBodies.register("body-1", EntityCapability.PLACE);

        JsonObject result = envelope("{\"body\":\"body-1\",\"item\":\"minecraft:stone\",\"x\":0,\"y\":64,\"z\":0}",
                ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.BODY, seen.get().kind());
    }

    @Test
    void aPlayerNamingNoBodyIsRefused() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        port(null, reached);
        enable();

        JsonObject refused = envelope(STONE, ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("INVALID_ARGS", refused.get("code").getAsString());
        assertEquals("body.target.required", refused.get("message").getAsString());
        assertFalse(reached.get(), "a call with no body must not reach the world");
    }

    @Test
    void anUnknownBodyIsUnavailable() throws Exception {
        enable();

        JsonObject refused = envelope("{\"body\":\"gone\",\"item\":\"minecraft:stone\",\"x\":0,\"y\":64,\"z\":0}",
                ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", refused.get("code").getAsString());
    }
}