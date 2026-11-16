package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.entity.ActionOutcome;
import com.rheinmetal.tianshu.api.entity.ActorPosition;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityActor;
import com.rheinmetal.tianshu.api.entity.EntityActions;
import com.rheinmetal.tianshu.api.entity.EntityProvider;
import com.rheinmetal.tianshu.api.entity.EntityMotionPort;
import com.rheinmetal.tianshu.api.entity.EntityQueryPort;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.EntityHit;
import com.rheinmetal.tianshu.api.entity.MoveOptions;
import com.rheinmetal.tianshu.api.entity.MoveResult;
import com.rheinmetal.tianshu.api.entity.ScanRequest;
import com.rheinmetal.tianshu.api.entity.ScanResult;
import com.rheinmetal.tianshu.api.assembly.ServerTickPort;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.InventorySnapshot;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyFollowToolTest {

    private final AtomicInteger moves = new AtomicInteger();

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
        TianshuTools.unregister(BodyFollowTool.ID);
        ClientTools.unregisterForTests();
        EntityActors.clearForTests();
        TianshuRuntimePorts.clear();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(BodyFollowTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(3, TimeUnit.SECONDS);
    }

    private void enable() {
        TianshuTools.register(new BodyFollowTool());
        TianshuTools.setEnabled(BodyFollowTool.ID, true);
    }

    private void installBody(double targetX) {
        EntityActors.register(new EntityProvider() {
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
                    public Set<EntityCapability> capabilities() {
                        return Set.of(EntityCapability.MOVE);
                    }
                });
            }

            @Override
            public Optional<EntityActions> actions(String bodyId) {
                return "body-1".equals(bodyId) ? Optional.of(handlers(targetX)) : Optional.empty();
            }
        });
    }

    private EntityActions handlers(double targetX) {
        return new EntityActions() {
            @Override
            public Optional<EntityQueryPort> query() {
                return Optional.of(new EntityQueryPort() {
                    @Override
                    public CompletableFuture<ScanResult> scan(ActorRef actor, ScanRequest request) {
                        return CompletableFuture.completedFuture(new ScanResult(List.of(),
                                List.of(new EntityHit("z-1", "minecraft:zombie", "minecraft:overworld",
                                        targetX, 64, 0))));
                    }

                    @Override
                    public CompletableFuture<InventorySnapshot> inventory(ActorRef actor) {
                        return CompletableFuture.completedFuture(new InventorySnapshot(List.of()));
                    }
                });
            }

            @Override
            public Optional<EntityMotionPort> motion() {
                return Optional.of(new EntityMotionPort() {
                    @Override
                    public CompletableFuture<MoveResult> move(ActorRef actor, ActorPosition target,
                                                             MoveOptions options) {
                        moves.incrementAndGet();
                        return CompletableFuture.completedFuture(new MoveResult(ActionOutcome.DONE, target, "moved"));
                    }

                    @Override
                    public CompletableFuture<com.rheinmetal.tianshu.api.entity.ActionResult> look(
                            ActorRef actor, ActorPosition target) {
                        throw new AssertionError("follow must not look");
                    }
                });
            }
        };
    }

    @Test
    void theSecondsBudgetStopsTheFollowAndReleasesTheBody() throws Exception {
        installBody(50.0D);
        // A tick port that holds the step instead of running it, so the test can let the budget expire first — this is
        // what a busy server looks like from the tool's side.
        java.util.List<Runnable> held = new java.util.ArrayList<>();
        TianshuRuntimePorts.register(ServerTickPort.class, task -> {
            held.add(task);
            return () -> {
            };
        });
        enable();

        JsonObject ignored = null;
        // Submitted without waiting: this future only completes when the follow stops, and the test is the one holding
        // the tick that would have stopped it.
        CompletableFuture<JsonObject> pending = TianshuTools.callJson(BodyFollowTool.ID,
                JsonParser.parseString("{\"body\":\"body-1\",\"entity\":\"minecraft:zombie\",\"seconds\":1}"),
                ToolCaller.player("p", true), ToolSide.SERVER);
        assertEquals(1, held.size(), "the follow should be waiting for its first tick");

        Thread.sleep(1_100L);
        held.get(0).run();

        JsonObject result = pending.get(3, TimeUnit.SECONDS);
        assertTrue(result.get("ok").getAsBoolean(), result.toString());

        Thread.sleep(1_100L);
        held.get(0).run();

        JsonObject follow = result.getAsJsonObject("result");
        assertEquals("REJECTED", follow.get("outcome").getAsString(),
                "a follow that used its budget must stop, not keep walking: " + follow);
        assertEquals(0, moves.get(), "no step may start after the budget is gone");
        assertTrue(BodyOperations.acquire("body-1").isEmpty(),
                "the body's operation slot must be free again once the follow stopped");
        BodyOperations.release("body-1");
    }

    @Test
    void aTargetAlreadyInReachCompletesWithoutMoving() throws Exception {
        installBody(1.0D);
        TianshuRuntimePorts.register(ServerTickPort.class, task -> {
            task.run();
            return () -> {
            };
        });
        enable();

        JsonObject result = envelope("{\"body\":\"body-1\",\"entity\":\"minecraft:zombie\"}",
                ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals("DONE", result.getAsJsonObject("result").get("outcome").getAsString());
        assertEquals(0, moves.get());
    }

    @Test
    void aMissingTargetEndsTheFollow() throws Exception {
        EntityActors.register(id -> "body-1".equals(id) ? Optional.of(new EntityActor() {
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
            public Set<EntityCapability> capabilities() {
                return Set.of(EntityCapability.MOVE);
            }
        }) : Optional.empty());
        TianshuRuntimePorts.register(ServerTickPort.class, task -> {
            task.run();
            return () -> {
            };
        });
        enable();

        JsonObject result = envelope("{\"body\":\"body-1\",\"entity\":\"minecraft:zombie\"}",
                ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals("REJECTED", result.getAsJsonObject("result").get("outcome").getAsString());
        assertEquals(0, moves.get());
    }

    @Test
    void withoutATickSourceFollowingIsUnavailable() throws Exception {
        installBody(1.0D);
        enable();

        JsonObject refused = envelope("{\"body\":\"body-1\",\"entity\":\"minecraft:zombie\"}",
                ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", refused.get("code").getAsString());
    }
}