package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.client.tool.routing.MotionRouter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.entity.ActionOutcome;
import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.entity.ActorPosition;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActionPort;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityActor;
import com.rheinmetal.tianshu.api.entity.EntityActions;
import com.rheinmetal.tianshu.api.entity.EntityItemPort;
import com.rheinmetal.tianshu.api.entity.EntityMotionPort;
import com.rheinmetal.tianshu.api.entity.BlockTarget;
import com.rheinmetal.tianshu.api.entity.BodyFailure;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.EntityTarget;
import com.rheinmetal.tianshu.api.entity.DropResult;
import com.rheinmetal.tianshu.api.entity.EquipRequest;
import com.rheinmetal.tianshu.api.entity.EquipResult;
import com.rheinmetal.tianshu.api.entity.MoveMode;
import com.rheinmetal.tianshu.api.entity.MoveOptions;
import com.rheinmetal.tianshu.api.tool.BodyAutonomy;
import com.rheinmetal.tianshu.api.tool.ServerBodyAutonomyCeiling;
import com.rheinmetal.tianshu.api.entity.MoveResult;
import com.rheinmetal.tianshu.api.entity.PickupResult;
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
    private Set<MoveMode> nativeModes = Set.of(MoveMode.WALK);
    private MoveMode executedMode;
    private boolean mineFails;
    private boolean denyMine;

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
        TianshuRuntimePorts.clear();
        EntityActors.clearForTests();
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
        EntityActors.register(new TestBodies.PlayerFixture() {
            @Override
            public Optional<EntityActor> resolve(String bodyId) {
                if (!"body-1".equals(bodyId)) {
                    return Optional.empty();
                }
                return Optional.of(new EntityActor() {
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
                    public Set<MoveMode> nativeMovementModes() {
                        return nativeModes;
                    }

                    @Override
                    public Set<EntityCapability> capabilities() {
                        return Set.of(EntityCapability.MOVE, EntityCapability.MINE, EntityCapability.INVENTORY);
                    }
                });
            }

            @Override
            public Optional<EntityActions> actions(String bodyId) {
                return "body-1".equals(bodyId) ? Optional.of(handlers()) : Optional.empty();
            }
        });
        TianshuRuntimePorts.register(EntityMotionPort.class, new MotionRouter());
        enable();
    }

    private EntityActions handlers() {
        return new EntityActions() {
            @Override
            public Optional<EntityMotionPort> motion() {
                return Optional.of(new EntityMotionPort() {
                    @Override
                    public CompletableFuture<MoveResult> move(ActorRef actor, ActorPosition target,
                                                             MoveOptions options) {
                        executedMode = options.mode();
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
            public Optional<EntityActionPort> action() {
                return Optional.of(new EntityActionPort() {
                    @Override
                    public CompletableFuture<ActionResult> mine(ActorRef actor, BlockTarget target) {
                        order.add("mine");
                        if (denyMine) {
                            throw BodyFailure.AUTONOMY_DENIED.exception("provider diagnostic for this body");
                        }
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
            public Optional<EntityItemPort> items() {
                return Optional.of(new EntityItemPort() {
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
    void survivalSelectsNativeMovementAndRejectsExtraTeleportBeforeTheNextStep() throws Exception {
        installBody();
        nativeModes = Set.of(MoveMode.FLY);
        JsonObject refused = envelope("""
                {"body":"body-1","steps":[{"kind":"move","x":1,"y":64,"z":1},
                {"kind":"move","mode":"teleport","x":2,"y":64,"z":2},{"kind":"pickup","radius":1}]}
                """, ToolCaller.player("p", true));
        assertEquals(MoveMode.FLY, executedMode);
        assertEquals(List.of("move"), order);
        assertEquals("NO_PERMISSION", refused.get("code").getAsString());
        assertEquals("body.autonomy.denied", refused.get("message").getAsString());
    }

    @Test
    void nativeTeleportIsSurvivalAndGrantedTeleportRequiresCheat() throws Exception {
        installBody();
        String steps = """
                {"body":"body-1","steps":[{"kind":"move","mode":"teleport","x":1,"y":64,"z":1}]}
                """;
        nativeModes = Set.of(MoveMode.TELEPORT);
        assertTrue(envelope(steps, ToolCaller.player("p", true)).get("ok").getAsBoolean());
        nativeModes = Set.of(MoveMode.WALK);
        TianshuRuntimePorts.register(ServerBodyAutonomyCeiling.class, () -> BodyAutonomy.CHEAT);
        assertTrue(envelope(steps, ToolCaller.player("p", true)).get("ok").getAsBoolean());
        assertEquals(MoveMode.TELEPORT, executedMode);
        assertEquals(List.of("move", "move"), order);
    }

    @Test
    void synchronousProviderRefusalKeepsItsReasonWhenItsDiagnosticChanges() throws Exception {
        denyMine = true;
        installBody();
        JsonObject refused = envelope("""
                {"body":"body-1","steps":[{"kind":"mine","x":1,"y":64,"z":1},{"kind":"pickup","radius":1}]}
                """, ToolCaller.player("p", true));

        assertEquals("NO_PERMISSION", refused.get("code").getAsString());
        assertEquals("body.autonomy.denied", refused.get("message").getAsString());
        assertEquals(List.of("mine"), order);
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