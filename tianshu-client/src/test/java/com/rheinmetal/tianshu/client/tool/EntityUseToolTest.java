package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.agent.ActionOutcome;
import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentEntityUsePort;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
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

class EntityUseToolTest {

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
        TianshuTools.unregister(EntityUseTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
        AgentBodies.clearForTests();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(EntityUseTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    private void enable() {
        TianshuTools.register(new EntityUseTool());
        TianshuTools.setEnabled(EntityUseTool.ID, true);
    }

    @Test
    void entityUseReachesThePortAsTheBody() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        AtomicReference<String> target = new AtomicReference<>();
        TianshuRuntimePorts.register(AgentEntityUsePort.class, (actor, entityId) -> {
            seen.set(actor);
            target.set(entityId);
            return CompletableFuture.completedFuture(new ActionResult(ActionOutcome.DONE, "fed"));
        });
        enable();
        TestBodies.register("body-1", BodyCapability.USE);

        JsonObject result = envelope("{\"body\":\"body-1\",\"entity\":\"00000000-0000-0000-0000-000000000001\"}",
                ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.BODY, seen.get().kind());
        assertEquals("00000000-0000-0000-0000-000000000001", target.get());
    }

    @Test
    void aBodyWithoutUseIsRefusedBeforeThePortIsTouched() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        TianshuRuntimePorts.register(AgentEntityUsePort.class, (actor, entityId) -> {
            reached.set(true);
            return CompletableFuture.completedFuture(new ActionResult(ActionOutcome.DONE, "fed"));
        });
        enable();
        TestBodies.register("float", BodyCapability.MOVE);

        JsonObject refused = envelope("{\"body\":\"float\",\"entity\":\"00000000-0000-0000-0000-000000000001\"}",
                ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNSUPPORTED_ACTOR", refused.get("code").getAsString());
        assertFalse(reached.get());
    }

    @Test
    void aMissingEntityIsInvalid() throws Exception {
        enable();
        TestBodies.register("body-1", BodyCapability.USE);

        JsonObject refused = envelope("{\"body\":\"body-1\"}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("INVALID_ARGS", refused.get("code").getAsString());
    }
}