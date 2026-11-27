package com.rheinmetal.tianshu.client.tool.routing;

import com.rheinmetal.tianshu.client.tool.TestBodies;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.entity.ActionOutcome;
import com.rheinmetal.tianshu.api.entity.ActorPosition;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityActor;
import com.rheinmetal.tianshu.api.entity.EntityActions;
import com.rheinmetal.tianshu.api.entity.EntityMotionPort;
import com.rheinmetal.tianshu.api.entity.EntityQueryPort;
import com.rheinmetal.tianshu.api.entity.BlockHit;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.MoveOptions;
import com.rheinmetal.tianshu.api.entity.MoveResult;
import com.rheinmetal.tianshu.api.entity.ScanRequest;
import com.rheinmetal.tianshu.api.entity.ScanResult;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.InventorySnapshot;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.client.tool.BodyMoveTool;
import com.rheinmetal.tianshu.client.tool.BodyOperations;
import com.rheinmetal.tianshu.client.tool.BodyScanTool;
import com.rheinmetal.tianshu.client.tool.ClientTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the extension path: a provider that is not the built-in carrier, with its own handlers, is reached by the
 * real routers and therefore by the real tools.
 */
class ForeignCarrierRoutingTest {

    private final AtomicBoolean scanned = new AtomicBoolean();
    private final AtomicBoolean moved = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        TianshuTools.clearRateLimits();
        ClientTools.register();
        EntityActors.register(new TestBodies.PlayerFixture() {
            @Override
            public Optional<EntityActor> resolve(String bodyId) {
                if (!"foreign-1".equals(bodyId)) {
                    return Optional.empty();
                }
                return Optional.of(new EntityActor() {
                    @Override
                    public String id() {
                        return "foreign-1";
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
                return "foreign-1".equals(bodyId) ? Optional.of(handlers()) : Optional.empty();
            }
        });
        TianshuRuntimePorts.register(EntityQueryPort.class, new QueryRouter(new EntityQueryPort() {
            @Override
            public CompletableFuture<ScanResult> scan(ActorRef actor, ScanRequest request) {
                throw new AssertionError("player fallback must not be used for a body");
            }

            @Override
            public CompletableFuture<InventorySnapshot> inventory(ActorRef actor) {
                throw new AssertionError("player fallback must not be used for a body");
            }
        }));
        TianshuRuntimePorts.register(EntityMotionPort.class, new MotionRouter());
        TianshuTools.register(new BodyScanTool());
        TianshuTools.register(new BodyMoveTool());
        TianshuTools.setEnabled(BodyMoveTool.ID, true);
    }

    @AfterEach
    void clean() {
        TianshuTools.unregister(BodyScanTool.ID);
        TianshuTools.unregister(BodyMoveTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
        EntityActors.clearForTests();
        BodyOperations.release("foreign-1");
    }

    private EntityActions handlers() {
        return new EntityActions() {
            @Override
            public Optional<EntityQueryPort> query() {
                return Optional.of(new EntityQueryPort() {
                    @Override
                    public CompletableFuture<ScanResult> scan(ActorRef actor, ScanRequest request) {
                        scanned.set(true);
                        return CompletableFuture.completedFuture(new ScanResult(
                                List.of(new BlockHit("minecraft:stone", "minecraft:overworld", 1, 64, 1)),
                                List.of()));
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
                        moved.set(true);
                        return CompletableFuture.completedFuture(new MoveResult(ActionOutcome.DONE, target, "moved"));
                    }

                    @Override
                    public CompletableFuture<com.rheinmetal.tianshu.api.entity.ActionResult> look(
                            ActorRef actor, ActorPosition target) {
                        throw new AssertionError("routing test must not look");
                    }
                });
            }
        };
    }

    private static JsonObject envelope(com.rheinmetal.tianshu.api.tool.ToolId id, String json) throws Exception {
        return TianshuTools.callJson(id, JsonParser.parseString(json), ToolCaller.player("p", true), ToolSide.SERVER)
                .get(3, TimeUnit.SECONDS);
    }

    @Test
    void aForeignProviderServesScanAndMove() throws Exception {
        JsonObject scan = envelope(BodyScanTool.ID, "{\"body\":\"foreign-1\",\"blocks\":\"minecraft:stone\"}");
        assertTrue(scan.get("ok").getAsBoolean());
        assertTrue(scanned.get());

        JsonObject move = envelope(BodyMoveTool.ID, "{\"body\":\"foreign-1\",\"x\":5,\"y\":64,\"z\":5}");
        assertTrue(move.get("ok").getAsBoolean());
        assertTrue(moved.get());
    }

    @Test
    void anUnknownBodyIsUnavailableThroughTheSameRouter() throws Exception {
        JsonObject refused = envelope(BodyScanTool.ID, "{\"body\":\"nobody\",\"blocks\":\"minecraft:stone\"}");

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", refused.get("code").getAsString());
    }
}