package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentItemPort;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyEquipToolTest {

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
        TianshuTools.unregister(BodyEquipTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
        AgentBodies.clearForTests();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(BodyEquipTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    private static void enable() {
        TianshuTools.register(new BodyEquipTool());
        TianshuTools.setEnabled(BodyEquipTool.ID, true);
    }

    @Test
    void aPlayerWithoutABodyCannotEquip() throws Exception {
        enable();

        JsonObject refused = envelope("{\"slot\":0}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("INVALID_ARGS", refused.get("code").getAsString());
    }

    @Test
    void aBodyWithoutAnInventoryIsRefusedBeforeThePortIsTouched() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        TianshuRuntimePorts.register(AgentItemPort.class, new AgentItemPort() {
            @Override
            public CompletableFuture<PickupResult> pickup(ActorRef actor, int radius) {
                throw new AssertionError("equip test must not pick up");
            }

            @Override
            public CompletableFuture<EquipResult> equip(ActorRef actor, int slot) {
                reached.set(true);
                return CompletableFuture.completedFuture(new EquipResult(slot, "", 0));
            }
        });
        enable();
        TestBodies.register("float", BodyCapability.MOVE);

        JsonObject refused = envelope("{\"body\":\"float\",\"slot\":0}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNSUPPORTED_ACTOR", refused.get("code").getAsString());
        assertFalse(reached.get());
    }

    @Test
    void equippingReachesThePortWithTheSlot() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        AtomicInteger slotSeen = new AtomicInteger(-2);
        TianshuRuntimePorts.register(AgentItemPort.class, new AgentItemPort() {
            @Override
            public CompletableFuture<PickupResult> pickup(ActorRef actor, int radius) {
                throw new AssertionError("equip test must not pick up");
            }

            @Override
            public CompletableFuture<EquipResult> equip(ActorRef actor, int slot) {
                seen.set(actor);
                slotSeen.set(slot);
                return CompletableFuture.completedFuture(new EquipResult(slot, "minecraft:iron_sword", 1));
            }
        });
        enable();
        TestBodies.register("body-1", BodyCapability.INVENTORY);

        JsonObject result = envelope("{\"body\":\"body-1\",\"slot\":3}", ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.BODY, seen.get().kind());
        assertEquals(3, slotSeen.get());
        assertEquals("minecraft:iron_sword",
                result.getAsJsonObject("result").get("itemId").getAsString());
    }

    @Test
    void aSlotOutOfRangeIsInvalid() throws Exception {
        enable();

        JsonObject refused = envelope("{\"body\":\"body-1\",\"slot\":99}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("INVALID_ARGS", refused.get("code").getAsString());
    }
}