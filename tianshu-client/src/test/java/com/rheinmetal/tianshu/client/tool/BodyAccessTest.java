package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.agent.ActorPosition;
import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBody;
import com.rheinmetal.tianshu.api.agent.AgentBodyProvider;
import com.rheinmetal.tianshu.api.agent.AgentMotionPort;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
import com.rheinmetal.tianshu.api.agent.MoveResult;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyAccessTest {

    private final AtomicBoolean motionReached = new AtomicBoolean();

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
        AgentBodies.clearForTests();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(BodyMoveTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    private void enable() {
        TianshuTools.register(new BodyMoveTool());
        TianshuTools.setEnabled(BodyMoveTool.ID, true);
    }

    /** Owner {@code p} only; anyone else is refused by the provider itself. */
    private void installGuardedBody() {
        AgentBodies.register(new AgentBodyProvider() {
            @Override
            public Optional<AgentBody> resolve(String bodyId) {
                if (!"body-1".equals(bodyId)) {
                    return Optional.empty();
                }
                return Optional.of(new AgentBody() {
                    @Override
                    public String id() {
                        return "body-1";
                    }

                    @Override
                    public boolean operator() {
                        return true;
                    }

                    @Override
                    public ActorPosition position() {
                        return new ActorPosition("minecraft:overworld", 0, 64, 0);
                    }

                    @Override
                    public Set<BodyCapability> capabilities() {
                        return Set.of(BodyCapability.MOVE);
                    }
                });
            }


            @Override
            public Optional<ToolException> checkAccess(ToolCaller caller, String bodyId) {
                return "p".equals(caller.id())
                        ? Optional.empty()
                        : Optional.of(new ToolException(ToolError.NO_PERMISSION, "not your body"));
            }
        });
        enable();
        TianshuRuntimePorts.register(AgentMotionPort.class, new AgentMotionPort() {
            @Override
            public CompletableFuture<MoveResult> move(com.rheinmetal.tianshu.api.agent.ActorRef actor,
                                                      ActorPosition target,
                                                      com.rheinmetal.tianshu.api.agent.MoveOptions options) {
                motionReached.set(true);
                return CompletableFuture.completedFuture(
                        new MoveResult(com.rheinmetal.tianshu.api.agent.ActionOutcome.DONE, target, "moved"));
            }

            @Override
            public CompletableFuture<ActionResult> look(com.rheinmetal.tianshu.api.agent.ActorRef actor,
                                                        ActorPosition target) {
                throw new AssertionError("access test must not look");
            }
        });
    }

    @Test
    void aPlayerWhoIsNotTheOwnerIsRefusedBeforeThePortIsTouched() throws Exception {
        installGuardedBody();

        JsonObject refused = envelope("{\"body\":\"body-1\",\"x\":1,\"y\":64,\"z\":1}",
                ToolCaller.player("intruder", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("NO_PERMISSION", refused.get("code").getAsString());
        assertFalse(motionReached.get());
    }

    @Test
    void theOwnerReachesThePort() throws Exception {
        installGuardedBody();

        JsonObject result = envelope("{\"body\":\"body-1\",\"x\":1,\"y\":64,\"z\":1}",
                ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertTrue(motionReached.get());
    }
}