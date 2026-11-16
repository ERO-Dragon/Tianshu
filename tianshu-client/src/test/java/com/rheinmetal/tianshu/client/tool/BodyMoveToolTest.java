package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.entity.ActionOutcome;
import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.entity.ActorPosition;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityMotionPort;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.MoveOptions;
import com.rheinmetal.tianshu.api.entity.MoveResult;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyMoveToolTest {

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
        TianshuTools.unregister(BodyMoveTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
        EntityActors.clearForTests();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(BodyMoveTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    @Test
    void aMoveReachesThePortAsABodyActor() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        TianshuRuntimePorts.register(EntityMotionPort.class, new EntityMotionPort() {
            @Override
            public CompletableFuture<MoveResult> move(ActorRef actor, ActorPosition target, MoveOptions options) {
                seen.set(actor);
                return CompletableFuture.completedFuture(new MoveResult(ActionOutcome.DONE, target, "moved"));
            }

            @Override
            public CompletableFuture<ActionResult> look(ActorRef actor, ActorPosition target) {
                throw new AssertionError("move must not look");
            }
        });
        TianshuTools.register(new BodyMoveTool());
        TianshuTools.setEnabled(BodyMoveTool.ID, true);
        TestBodies.register("body-1", EntityCapability.MOVE);

        JsonObject envelope = envelope("{\"body\":\"body-1\",\"x\":10,\"y\":64,\"z\":-3}",
                ToolCaller.player("p", true));
        assertTrue(envelope.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.BODY, seen.get().kind());
        assertEquals("body-1", seen.get().id());
    }

    @Test
    void aBodyWithoutMoveIsRefusedAsUnsupported() throws Exception {
        TianshuTools.register(new BodyMoveTool());
        TianshuTools.setEnabled(BodyMoveTool.ID, true);
        TestBodies.register("still", EntityCapability.LOOK);

        JsonObject refused = envelope("{\"body\":\"still\",\"x\":0,\"y\":64,\"z\":0}",
                ToolCaller.player("p", true));
        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNSUPPORTED_ACTOR", refused.get("code").getAsString());
    }

    @Test
    void anUnknownBodyIsUnavailable() throws Exception {
        TianshuTools.register(new BodyMoveTool());
        TianshuTools.setEnabled(BodyMoveTool.ID, true);

        JsonObject refused = envelope("{\"body\":\"gone\",\"x\":0,\"y\":64,\"z\":0}",
                ToolCaller.player("p", true));
        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", refused.get("code").getAsString());
    }
}