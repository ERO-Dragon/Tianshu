package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.capability.CapabilitySharing;
import com.rheinmetal.tianshu.api.capability.CapabilitySubscription;
import com.rheinmetal.tianshu.api.capability.CapabilitySubscriptions;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.entity.ActorPosition;
import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityActor;
import com.rheinmetal.tianshu.api.entity.EntityProvider;
import com.rheinmetal.tianshu.api.entity.EntityMotionPort;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.MoveResult;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyAccessTest {

    private static final UUID CREATOR = UUID.randomUUID();

    @BeforeEach
    void subscribeToFixtures() {
        CapabilitySubscriptions.declare("module.test", CapabilitySubscription.atomicFrom(CapabilitySharing.BASE_PROVIDER));
        CapabilitySubscriptions.declare("module.other", CapabilitySubscription.atomicFrom(CapabilitySharing.BASE_PROVIDER));
    }

    @AfterEach
    void withdrawSubscriptions() {
        CapabilitySubscriptions.withdraw("module.test");
        CapabilitySubscriptions.withdraw("module.other");
    }


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
        EntityActors.clearForTests();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(BodyMoveTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    private void enable() {
        TianshuTools.registerBuiltin(new BodyMoveTool());
        TianshuTools.setEnabled(BodyMoveTool.ID, true);
    }

    /** Owner {@code p} only; anyone else is refused by the provider itself. */
    private void installGuardedBody() {
        installBody("body-1", "module.test");
        enable();
        TianshuRuntimePorts.register(EntityMotionPort.class, new EntityMotionPort() {
            @Override
            public CompletableFuture<MoveResult> move(com.rheinmetal.tianshu.api.entity.ActorRef actor,
                                                      ActorPosition target,
                                                      com.rheinmetal.tianshu.api.entity.MoveOptions options) {
                motionReached.set(true);
                return CompletableFuture.completedFuture(
                        new MoveResult(com.rheinmetal.tianshu.api.entity.ActionOutcome.DONE, target, "moved"));
            }

            @Override
            public CompletableFuture<ActionResult> look(com.rheinmetal.tianshu.api.entity.ActorRef actor,
                                                        ActorPosition target) {
                throw new AssertionError("access test must not look");
            }
        });
    }

    private void installBody(String id, String module) {
        EntityActors.register(new EntityProvider() {
            @Override
            public Optional<EntityActor> resolve(String bodyId) {
                if (!id.equals(bodyId)) {
                    return Optional.empty();
                }
                return Optional.of(new EntityActor() {
                    @Override
                    public String id() {
                        return id;
                    }

                    @Override
                    public String ownerModule() {
                        return module;
                    }

                    @Override
                    public UUID ownerPlayer() {
                        return CREATOR;
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
                    public Set<EntityCapability> capabilities() {
                        return Set.of(EntityCapability.MOVE);
                    }
                });
            }

            @Override
            public Optional<ToolException> checkAccess(ToolCaller caller, String bodyId) {
                return CREATOR.toString().equals(caller.id())
                        ? Optional.empty()
                        : Optional.of(new ToolException(ToolError.NO_PERMISSION, "not your body"));
            }
        });
    }

    @Test
    void twoAgentsInTheSamePlayerSessionCanOnlyMoveTheirOwnBodies() throws Exception {
        installGuardedBody();
        installBody("body-2", "module.other");
        var first = ToolCaller.agentFor("module.test", CREATOR.toString(), false, ToolAutonomy.CONSERVATIVE);
        var second = ToolCaller.agentFor("module.other", CREATOR.toString(), false, ToolAutonomy.CONSERVATIVE);
        String moveFirst = "{\"body\":\"body-1\",\"x\":1,\"y\":64,\"z\":1}";
        String moveSecond = "{\"body\":\"body-2\",\"x\":1,\"y\":64,\"z\":1}";

        assertEquals("body.controller.mismatch", envelope(moveSecond, first).get("message").getAsString());
        assertEquals("body.controller.mismatch", envelope(moveFirst, second).get("message").getAsString());
        assertFalse(motionReached.get());
        assertTrue(envelope(moveFirst, first).get("ok").getAsBoolean());
        assertTrue(envelope(moveSecond, second).get("ok").getAsBoolean());
    }

    @Test
    void anotherPlayerUsingTheSameModuleCannotMoveThisBody() throws Exception {
        installGuardedBody();
        var visitor = ToolCaller.agentFor("module.test", UUID.randomUUID().toString(), false, ToolAutonomy.CONSERVATIVE);
        var result = envelope("{\"body\":\"body-1\",\"x\":1,\"y\":64,\"z\":1}", visitor);
        assertEquals("body.access.denied", result.get("message").getAsString());
        assertFalse(motionReached.get());
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
                ToolCaller.player(CREATOR.toString(), true));

        assertTrue(result.get("ok").getAsBoolean());
        assertTrue(motionReached.get());
    }

    @Test
    void anAgentDrivesTheBodyItNames() throws Exception {
        installGuardedBody();

        // DEC-2: an AI module may drive a body. Before this, the same call was answered NO_PERMISSION, so the whole
        // body layer was unreachable from the agent — the only caller these tools exist for.
        JsonObject result = envelope("{\"body\":\"body-1\",\"x\":1,\"y\":64,\"z\":1}",
                ToolCaller.agent("module.test", true, ToolAutonomy.CONSERVATIVE));

        assertTrue(result.get("ok").getAsBoolean(), result.toString());
        assertTrue(motionReached.get());
    }

    @Test
    void anotherModuleCannotDriveTheBodyEvenWithOperatorStanding() throws Exception {
        installGuardedBody();
        JsonObject result = envelope("{\"body\":\"body-1\",\"x\":1,\"y\":64,\"z\":1}",
                ToolCaller.agent("module.other", true, ToolAutonomy.FULL));
        assertEquals("NO_PERMISSION", result.get("code").getAsString());
        assertFalse(motionReached.get());
    }

    @Test
    void anAgentThatNamesNoBodyIsRefusedInsteadOfActingAsItself() {
        installGuardedBody();

        ToolException refusal = ActorScope.body(ToolCaller.agent("module.test", true, ToolAutonomy.CONSERVATIVE),
                null).refusal().orElseThrow();

        assertEquals(ToolError.INVALID_ARGS, refusal.code());
        assertFalse(motionReached.get());
    }

    @Test
    void aBodyNamingAnotherBodyIsRefusedInsteadOfIgnoringIt() {
        installGuardedBody();

        ToolException refusal = ActorScope.body(ToolCaller.body("body-1", true), "body-2")
                .refusal().orElseThrow();

        assertEquals(ToolError.INVALID_ARGS, refusal.code());
        assertEquals("body.target.mismatch", refusal.getMessage());
    }
}