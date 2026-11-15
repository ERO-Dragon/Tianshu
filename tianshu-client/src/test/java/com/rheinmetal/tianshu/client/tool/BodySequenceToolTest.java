package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.agent.ActionOutcome;
import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.ActorPosition;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentActionPort;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBody;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.agent.AgentBodyProvider;
import com.rheinmetal.tianshu.api.agent.AgentItemPort;
import com.rheinmetal.tianshu.api.agent.AgentMotionPort;
import com.rheinmetal.tianshu.api.agent.BlockTarget;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
import com.rheinmetal.tianshu.api.agent.EntityTarget;
import com.rheinmetal.tianshu.api.agent.DropResult;
import com.rheinmetal.tianshu.api.agent.EquipRequest;
import com.rheinmetal.tianshu.api.agent.EquipResult;
import com.rheinmetal.tianshu.api.agent.MoveOptions;
import com.rheinmetal.tianshu.api.agent.MoveResult;
import com.rheinmetal.tianshu.api.agent.PickupResult;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodySequenceToolTest {

    private final List<String> order = new ArrayList<>();
    private boolean mineFails;

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
        TianshuTools.unregister(BodySequenceTool.ID);
        ClientTools.unregisterForTests();
        AgentBodies.clearForTests();
        BodyOperations.release("body-1");
    }

    private JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(BodySequenceTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(3, TimeUnit.SECONDS);
    }

    private void enable() {
        TianshuTools.register(new BodySequenceTool());
        TianshuTools.setEnabled(BodySequenceTool.ID, true);
    }

    private void installBody() {
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
                        return Set.of(BodyCapability.MOVE, BodyCapability.MINE, BodyCapability.INVENTORY);
                    }
                });
            }

            @Override
            public Optional<AgentBodyActions> actions(String bodyId) {
                return "body-1".equals(bodyId) ? Optional.of(handlers()) : Optional.empty();
            }
        });
        enable();
    }

    private AgentBodyActions handlers() {
        return new AgentBodyActions() {
            @Override
            public Optional<AgentMotionPort> motion() {
                return Optional.of(new AgentMotionPort() {
                    @Override
                    public CompletableFuture<MoveResult> move(ActorRef actor, ActorPosition target,
                                                             MoveOptions options) {
                        order.add("move");
                        return CompletableFuture.completedFuture(new MoveResult(ActionOutcome.DONE, target, "moved"));
                    }

                    @Override
                    public CompletableFuture<ActionResult> look(ActorRef actor, ActorPosition target) {
                        throw new AssertionError("sequence test must not look");
                    }
                });
            }

            @Override
            public Optional<AgentActionPort> action() {
                return Optional.of(new AgentActionPort() {
                    @Override
                    public CompletableFuture<ActionResult> mine(ActorRef actor, BlockTarget target) {
                        order.add("mine");
                        return CompletableFuture.completedFuture(mineFails
                                ? ActionResult.rejected("blocked")
                                : ActionResult.done("mined"));
                    }

                    @Override
                    public CompletableFuture<ActionResult> attack(ActorRef actor, EntityTarget target) {
                        throw new AssertionError("sequence test must not attack");
                    }
                });
            }

            @Override
            public Optional<AgentItemPort> items() {
                return Optional.of(new AgentItemPort() {
                    @Override
                    public CompletableFuture<PickupResult> pickup(ActorRef actor, int radius) {
                        order.add("pickup");
                        return CompletableFuture.completedFuture(new PickupResult(List.of()));
                    }

                    @Override
                    public CompletableFuture<DropResult> drop(ActorRef actor, int slot, int count) {
                throw new AssertionError("this test must not drop");
            }

            @Override
            public CompletableFuture<EquipResult> equip(ActorRef actor, EquipRequest request) {
                        order.add("equip");
                        return CompletableFuture.completedFuture(
                                new EquipResult(request.target(), request.inventorySlot(), "minecraft:stone", 1));
                    }
                });
            }
        };
    }

    @Test
    void stepsRunInOrder() throws Exception {
        installBody();

        JsonObject result = envelope("""
                {"body":"body-1","steps":[
                  {"kind":"move","x":1,"y":64,"z":1},
                  {"kind":"mine","x":2,"y":64,"z":2},
                  {"kind":"pickup","radius":2}
                ]}
                """, ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(List.of("move", "mine", "pickup"), order);
        JsonObject sequence = result.getAsJsonObject("result");
        assertEquals(-1, sequence.get("failedIndex").getAsInt());
        assertEquals(3, sequence.getAsJsonArray("steps").size());
    }

    @Test
    void theFirstFailureStopsTheSequence() throws Exception {
        mineFails = true;
        installBody();

        JsonObject result = envelope("""
                {"body":"body-1","steps":[
                  {"kind":"move","x":1,"y":64,"z":1},
                  {"kind":"mine","x":2,"y":64,"z":2},
                  {"kind":"pickup","radius":2}
                ]}
                """, ToolCaller.player("p", true));

        // A stopped sequence is a failed call. It used to answer ok:true with failedIndex:1 inside the result, so an
        // orchestration layer that only reads the top-level ok saw "the plan worked" while one step had run.
        assertFalse(result.get("ok").getAsBoolean(), result.toString());
        assertTrue(result.get("message").getAsString().contains("stopped at step 1"), result.toString());
        assertEquals(List.of("move", "mine"), order, "the sequence must still stop where it failed");
    }

    @Test
    void anUnknownStepKindIsInvalid() throws Exception {
        installBody();

        JsonObject refused = envelope("{\"body\":\"body-1\",\"steps\":[{\"kind\":\"dance\"}]}",
                ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("INVALID_ARGS", refused.get("code").getAsString());
    }

    @Test
    void tooManyStepsIsInvalid() throws Exception {
        installBody();
        StringBuilder steps = new StringBuilder();
        for (int index = 0; index < 33; index++) {
            if (index > 0) {
                steps.append(',');
            }
            steps.append("{\"kind\":\"move\",\"x\":1,\"y\":64,\"z\":1}");
        }

        JsonObject refused = envelope("{\"body\":\"body-1\",\"steps\":[" + steps + "]}",
                ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("INVALID_ARGS", refused.get("code").getAsString());
    }
}