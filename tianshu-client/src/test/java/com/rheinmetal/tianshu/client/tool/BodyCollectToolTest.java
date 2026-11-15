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
import com.rheinmetal.tianshu.api.agent.BodySlot;
import com.rheinmetal.tianshu.api.agent.DropResult;
import com.rheinmetal.tianshu.api.agent.EquipRequest;
import com.rheinmetal.tianshu.api.agent.AgentMotionPort;
import com.rheinmetal.tianshu.api.agent.AgentWorldQueryPort;
import com.rheinmetal.tianshu.api.agent.BlockHit;
import com.rheinmetal.tianshu.api.agent.BlockTarget;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
import com.rheinmetal.tianshu.api.agent.EquipResult;
import com.rheinmetal.tianshu.api.agent.MoveOptions;
import com.rheinmetal.tianshu.api.agent.MoveResult;
import com.rheinmetal.tianshu.api.agent.PickedItem;
import com.rheinmetal.tianshu.api.agent.PickupResult;
import com.rheinmetal.tianshu.api.agent.ScanRequest;
import com.rheinmetal.tianshu.api.agent.ScanResult;
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

class BodyCollectToolTest {

    private final AtomicInteger mines = new AtomicInteger();
    private final AtomicInteger pickups = new AtomicInteger();
    private final AtomicInteger moves = new AtomicInteger();
    /** What the fake pickup reports as collected; an empty list is "the drop never reached the body". */
    private List<PickedItem> pickupYield = List.of(new PickedItem("minecraft:stone", 1));

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
        TianshuTools.unregister(BodyCollectTool.ID);
        ClientTools.unregisterForTests();
        AgentBodies.clearForTests();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(BodyCollectTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(3, TimeUnit.SECONDS);
    }

    private void enable() {
        TianshuTools.register(new BodyCollectTool());
        TianshuTools.setEnabled(BodyCollectTool.ID, true);
    }

    private void installWorld() {
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
    }

    private AgentBodyActions handlers() {
        return new AgentBodyActions() {
            @Override
            public Optional<AgentWorldQueryPort> query() {
                return Optional.of(new AgentWorldQueryPort() {
                    @Override
                    public CompletableFuture<ScanResult> scan(ActorRef actor, ScanRequest request) {
                        return CompletableFuture.completedFuture(new ScanResult(
                                List.of(new BlockHit("minecraft:stone", "minecraft:overworld", 2, 64, 0)),
                                List.of()));
                    }

                    @Override
                    public CompletableFuture<InventorySnapshot> inventory(ActorRef actor) {
                        return CompletableFuture.completedFuture(new InventorySnapshot(List.of()));
                    }
                });
            }

            @Override
            public Optional<AgentMotionPort> motion() {
                return Optional.of(new AgentMotionPort() {
                    @Override
                    public CompletableFuture<MoveResult> move(ActorRef actor, ActorPosition target,
                                                             MoveOptions options) {
                        moves.incrementAndGet();
                        return CompletableFuture.completedFuture(new MoveResult(ActionOutcome.DONE, target, "moved"));
                    }

                    @Override
                    public CompletableFuture<ActionResult> look(ActorRef actor, ActorPosition target) {
                        throw new AssertionError("collect must not look");
                    }
                });
            }

            @Override
            public Optional<AgentActionPort> action() {
                return Optional.of(new AgentActionPort() {
                    @Override
                    public CompletableFuture<ActionResult> mine(ActorRef actor, BlockTarget target) {
                        mines.incrementAndGet();
                        return CompletableFuture.completedFuture(ActionResult.done("mined"));
                    }

                    @Override
                    public CompletableFuture<ActionResult> attack(ActorRef actor,
                                                                  com.rheinmetal.tianshu.api.agent.EntityTarget target) {
                        throw new AssertionError("collect must not attack");
                    }
                });
            }

            @Override
            public Optional<AgentItemPort> items() {
                return Optional.of(new AgentItemPort() {
                    @Override
                    public CompletableFuture<PickupResult> pickup(ActorRef actor, int radius) {
                        pickups.incrementAndGet();
                        return CompletableFuture.completedFuture(new PickupResult(pickupYield));
                    }

                    @Override
                    public CompletableFuture<DropResult> drop(ActorRef actor, int slot, int count) {
                throw new AssertionError("this test must not drop");
            }

            @Override
            public CompletableFuture<EquipResult> equip(ActorRef actor, EquipRequest request) {
                        throw new AssertionError("collect must not equip");
                    }
                });
            }
        };
    }

    @Test
    void collectingMinesAndPicksUpUntilTheCountIsMet() throws Exception {
        installWorld();
        enable();

        JsonObject result = envelope("{\"body\":\"body-1\",\"block\":\"minecraft:stone\",\"count\":2}",
                ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(2, mines.get());
        assertEquals(2, pickups.get());
        assertEquals(2, moves.get());
        assertEquals(2, result.getAsJsonObject("result").get("collected").getAsInt());
    }

    /**
     * {@code collected} exists to tell the caller what it actually owns. A block that broke but whose drop
     * never reached the body must not be counted: the agent would go on to craft with materials it does not
     * have, and nothing in the answer would explain the failure later.
     */
    @Test
    void aPickupThatCollectsNothingIsNotCounted() throws Exception {
        pickupYield = List.of();
        installWorld();
        enable();

        JsonObject result = envelope("{\"body\":\"body-1\",\"block\":\"minecraft:stone\",\"count\":2}",
                ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(1, mines.get(), "it must stop instead of mining blocks whose drops vanish");
        assertEquals(1, pickups.get());

        JsonObject value = result.getAsJsonObject("result");
        assertEquals(0, value.get("collected").getAsInt(), "nothing was collected, so nothing may be reported");
        assertEquals(2, value.get("remaining").getAsInt());
        assertTrue(value.get("detail").getAsString().contains("nothing was picked up"),
                "the answer has to say why it stopped: " + value);
    }

    @Test
    void aBodyWithoutMiningIsRefused() throws Exception {
        AgentBodies.register(id -> "float".equals(id) ? Optional.of(new AgentBody() {
            @Override
            public String id() {
                return "float";
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
        }) : Optional.empty());
        enable();

        JsonObject refused = envelope("{\"body\":\"float\",\"block\":\"minecraft:stone\"}",
                ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNSUPPORTED_ACTOR", refused.get("code").getAsString());
        assertEquals(0, mines.get());
    }

    @Test
    void aMissingBlockIdIsInvalid() throws Exception {
        enable();

        JsonObject refused = envelope("{\"body\":\"body-1\"}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("INVALID_ARGS", refused.get("code").getAsString());
    }
}