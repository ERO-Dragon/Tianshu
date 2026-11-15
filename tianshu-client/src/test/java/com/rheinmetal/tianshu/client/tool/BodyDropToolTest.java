package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentItemPort;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
import com.rheinmetal.tianshu.api.agent.DropResult;
import com.rheinmetal.tianshu.api.agent.EquipRequest;
import com.rheinmetal.tianshu.api.agent.EquipResult;
import com.rheinmetal.tianshu.api.agent.PickupResult;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyDropToolTest {

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
        TianshuTools.unregister(BodyDropTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
        AgentBodies.clearForTests();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(BodyDropTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    private void enable() {
        TianshuTools.register(new BodyDropTool());
        TianshuTools.setEnabled(BodyDropTool.ID, true);
    }

    @Test
    void droppingReachesThePortWithSlotAndCount() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        AtomicInteger slotSeen = new AtomicInteger(-1);
        AtomicInteger countSeen = new AtomicInteger(-1);
        TianshuRuntimePorts.register(AgentItemPort.class, new AgentItemPort() {
            @Override
            public CompletableFuture<PickupResult> pickup(ActorRef actor, int radius) {
                throw new AssertionError("drop test must not pick up");
            }

            @Override
            public CompletableFuture<DropResult> drop(ActorRef actor, int slot, int count) {
                seen.set(actor);
                slotSeen.set(slot);
                countSeen.set(count);
                return CompletableFuture.completedFuture(new DropResult("minecraft:stone", 3));
            }

            @Override
            public CompletableFuture<EquipResult> equip(ActorRef actor, EquipRequest request) {
                throw new AssertionError("drop test must not equip");
            }
        });
        enable();
        TestBodies.register("body-1", BodyCapability.INVENTORY);

        JsonObject result = envelope("{\"body\":\"body-1\",\"slot\":2,\"count\":3}",
                ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.BODY, seen.get().kind());
        assertEquals(2, slotSeen.get());
        assertEquals(3, countSeen.get());
    }

    @Test
    void aBodyWithoutAnInventoryIsRefusedBeforeThePortIsTouched() throws Exception {
        AtomicInteger reached = new AtomicInteger();
        TianshuRuntimePorts.register(AgentItemPort.class, new AgentItemPort() {
            @Override
            public CompletableFuture<PickupResult> pickup(ActorRef actor, int radius) {
                throw new AssertionError("drop test must not pick up");
            }

            @Override
            public CompletableFuture<DropResult> drop(ActorRef actor, int slot, int count) {
                reached.incrementAndGet();
                return CompletableFuture.completedFuture(new DropResult("", 0));
            }

            @Override
            public CompletableFuture<EquipResult> equip(ActorRef actor, EquipRequest request) {
                throw new AssertionError("drop test must not equip");
            }
        });
        enable();
        TestBodies.register("float", BodyCapability.MOVE);

        JsonObject refused = envelope("{\"body\":\"float\",\"slot\":0}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNSUPPORTED_ACTOR", refused.get("code").getAsString());
        assertEquals(0, reached.get());
    }

    @Test
    void aSlotOutOfRangeIsInvalid() throws Exception {
        enable();
        TestBodies.register("body-1", BodyCapability.INVENTORY);

        JsonObject refused = envelope("{\"body\":\"body-1\",\"slot\":99}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("INVALID_ARGS", refused.get("code").getAsString());
    }
}