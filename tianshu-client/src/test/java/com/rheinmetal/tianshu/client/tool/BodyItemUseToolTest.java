package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.entity.ActionOutcome;
import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityItemUsePort;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.ItemUseTarget;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item use is body-only: what a player does with the item in their own hand is not the platform's call.
 */
class BodyItemUseToolTest {

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
        TianshuTools.unregister(BodyItemUseTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
        EntityActors.clearForTests();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(BodyItemUseTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    private void enable() {
        TianshuTools.register(new BodyItemUseTool());
        TianshuTools.setEnabled(BodyItemUseTool.ID, true);
    }

    @Test
    void aBodyUsesTheItemInTheAir() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        AtomicInteger holdSeen = new AtomicInteger(-1);
        TianshuRuntimePorts.register(EntityItemUsePort.class, (actor, target, holdTicks) -> {
            reached.set(true);
            seen.set(actor);
            holdSeen.set(holdTicks);
            assertTrue(target instanceof ItemUseTarget.Air);
            return CompletableFuture.completedFuture(new ActionResult(ActionOutcome.DONE, "used"));
        });
        enable();
        TestBodies.register("body-1", EntityCapability.USE, EntityCapability.INVENTORY);

        JsonObject result = envelope("{\"body\":\"body-1\",\"holdTicks\":12}", ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertTrue(reached.get());
        assertEquals(ActorRef.Kind.BODY, seen.get().kind());
        assertEquals(12, holdSeen.get());
    }

    @Test
    void aBodyWithoutUseIsRefusedBeforeThePortIsTouched() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        TianshuRuntimePorts.register(EntityItemUsePort.class, (actor, target, holdTicks) -> {
            reached.set(true);
            return CompletableFuture.completedFuture(new ActionResult(ActionOutcome.DONE, "used"));
        });
        enable();
        TestBodies.register("float", EntityCapability.MOVE, EntityCapability.INVENTORY);

        JsonObject refused = envelope("{\"body\":\"float\"}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNSUPPORTED_ACTOR", refused.get("code").getAsString());
        assertFalse(reached.get());
    }

    @Test
    void aPlayerMustNameABody() throws Exception {
        enable();

        JsonObject refused = envelope("{}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("INVALID_ARGS", refused.get("code").getAsString());
        assertEquals("body.target.required", refused.get("message").getAsString());
    }
}