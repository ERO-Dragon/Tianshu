package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.agent.ActionOutcome;
import com.rheinmetal.tianshu.api.agent.ActorPosition;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBody;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.agent.AgentBodyProvider;
import com.rheinmetal.tianshu.api.agent.AgentMotionPort;
import com.rheinmetal.tianshu.api.agent.AgentWorldQueryPort;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
import com.rheinmetal.tianshu.api.agent.EntityHit;
import com.rheinmetal.tianshu.api.agent.MoveOptions;
import com.rheinmetal.tianshu.api.agent.MoveResult;
import com.rheinmetal.tianshu.api.agent.ScanRequest;
import com.rheinmetal.tianshu.api.agent.ScanResult;
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
        AgentBodies.clearForTests();
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
            public Optional<AgentBodyActions> actions(String bodyId) {
                return "body-1".equals(bodyId) ? Optional.of(handlers(targetX)) : Optional.empty();
            }
        });
    }

    private AgentBodyActions handlers(double targetX) {
        return new AgentBodyActions() {
            @Override
            public Optional<AgentWorldQueryPort> query() {
                return Optional.of(new AgentWorldQueryPort() {
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
            public Optional<AgentMotionPort> motion() {
                return Optional.of(new AgentMotionPort() {
                    @Override
                    public CompletableFuture<MoveResult> move(ActorRef actor, ActorPosition target,
                                                             MoveOptions options) {
                        moves.incrementAndGet();
                        return CompletableFuture.completedFuture(new MoveResult(ActionOutcome.DONE, target, "moved"));
                    }

                    @Override
                    public CompletableFuture<com.rheinmetal.tianshu.api.agent.ActionResult> look(
                            ActorRef actor, ActorPosition target) {
                        throw new AssertionError("follow must not look");
                    }
                });
            }
        };
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
        AgentBodies.register(id -> "body-1".equals(id) ? Optional.of(new AgentBody() {
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