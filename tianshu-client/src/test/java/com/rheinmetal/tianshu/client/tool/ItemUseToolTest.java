package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.agent.ActionOutcome;
import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentItemUsePort;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
import com.rheinmetal.tianshu.api.agent.ItemUseTarget;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUseToolTest {

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
        TianshuTools.unregister(ItemUseTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
        AgentBodies.clearForTests();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(ItemUseTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    private void enable() {
        TianshuTools.register(new ItemUseTool());
        TianshuTools.setEnabled(ItemUseTool.ID, true);
    }

    @Test
    void usingAnItemOnTheAirReachesThePortAsTheBody() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        TianshuRuntimePorts.register(AgentItemUsePort.class, (actor, target) -> {
            reached.set(true);
            seen.set(actor);
            assertTrue(target instanceof ItemUseTarget.Air);
            return CompletableFuture.completedFuture(new ActionResult(ActionOutcome.DONE, "used"));
        });
        enable();
        TestBodies.register("body-1", BodyCapability.USE, BodyCapability.INVENTORY);

        JsonObject result = envelope("{\"body\":\"body-1\"}", ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertTrue(reached.get());
        assertEquals(ActorRef.Kind.BODY, seen.get().kind());
    }

    @Test
    void aBodyWithoutUseIsRefusedBeforeThePortIsTouched() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        TianshuRuntimePorts.register(AgentItemUsePort.class, (actor, target) -> {
            reached.set(true);
            return CompletableFuture.completedFuture(new ActionResult(ActionOutcome.DONE, "used"));
        });
        enable();
        TestBodies.register("float", BodyCapability.MOVE, BodyCapability.INVENTORY);

        JsonObject refused = envelope("{\"body\":\"float\"}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNSUPPORTED_ACTOR", refused.get("code").getAsString());
        assertFalse(reached.get());
    }

    @Test
    void aPlayerWithoutABodyCannotUseItemsThroughTheTool() throws Exception {
        enable();

        JsonObject refused = envelope("{}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("INVALID_ARGS", refused.get("code").getAsString());
    }
}