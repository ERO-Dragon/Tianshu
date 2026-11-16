package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityItemPort;
import com.rheinmetal.tianshu.api.entity.EntitySlot;
import com.rheinmetal.tianshu.api.entity.DropResult;
import com.rheinmetal.tianshu.api.entity.EquipRequest;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.EquipResult;
import com.rheinmetal.tianshu.api.entity.PickupResult;
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
        EntityActors.clearForTests();
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
        TianshuRuntimePorts.register(EntityItemPort.class, new EntityItemPort() {
            @Override
            public CompletableFuture<PickupResult> pickup(ActorRef actor, int radius) {
                throw new AssertionError("equip test must not pick up");
            }

            @Override
            public CompletableFuture<DropResult> drop(ActorRef actor, int slot, int count) {
                throw new AssertionError("this test must not drop");
            }

            @Override
            public CompletableFuture<EquipResult> equip(ActorRef actor, EquipRequest request) {
                reached.set(true);
                return CompletableFuture.completedFuture(new EquipResult(EntitySlot.MAIN_HAND, -1, "", 0));
            }
        });
        enable();
        TestBodies.register("float", EntityCapability.MOVE);

        JsonObject refused = envelope("{\"body\":\"float\",\"slot\":0}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNSUPPORTED_ACTOR", refused.get("code").getAsString());
        assertFalse(reached.get());
    }

    @Test
    void equippingReachesThePortWithTheSlot() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        AtomicInteger slotSeen = new AtomicInteger(-2);
        TianshuRuntimePorts.register(EntityItemPort.class, new EntityItemPort() {
            @Override
            public CompletableFuture<PickupResult> pickup(ActorRef actor, int radius) {
                throw new AssertionError("equip test must not pick up");
            }

            @Override
            public CompletableFuture<DropResult> drop(ActorRef actor, int slot, int count) {
                throw new AssertionError("this test must not drop");
            }

            @Override
            public CompletableFuture<EquipResult> equip(ActorRef actor, EquipRequest request) {
                seen.set(actor);
                slotSeen.set(request.inventorySlot());
                return CompletableFuture.completedFuture(new EquipResult(EntitySlot.MAIN_HAND, request.inventorySlot(), "minecraft:iron_sword", 1));
            }
        });
        enable();
        TestBodies.register("body-1", EntityCapability.INVENTORY);

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

    /**
     * Every name the schema advertises has to be a name the parser accepts.
     *
     * <p>Both sides read {@link EntitySlot#wireNameList()}, which is the point: the previous version advertised
     * {@code main}/{@code offhand} and parsed {@code MAIN}/{@code OFFHAND}, so a model following the description
     * was refused every time and had nothing to correct itself with.</p>
     */
    @Test
    void everySlotNameTheSchemaAdvertisesIsAccepted() throws Exception {
        AtomicReference<EntitySlot> seen = new AtomicReference<>();
        TianshuRuntimePorts.register(EntityItemPort.class, new EntityItemPort() {
            @Override
            public CompletableFuture<PickupResult> pickup(ActorRef actor, int radius) {
                throw new AssertionError("equip test must not pick up");
            }

            @Override
            public CompletableFuture<DropResult> drop(ActorRef actor, int slot, int count) {
                throw new AssertionError("this test must not drop");
            }

            @Override
            public CompletableFuture<EquipResult> equip(ActorRef actor, EquipRequest request) {
                seen.set(request.target());
                return CompletableFuture.completedFuture(
                        new EquipResult(request.target(), request.inventorySlot(), "", 1));
            }
        });
        enable();
        TestBodies.register("body-1", EntityCapability.INVENTORY);

        String description = new BodyEquipTool().argsSchema().getAsJsonObject("properties")
                .getAsJsonObject("target").get("description").getAsString();

        for (EntitySlot slot : EntitySlot.values()) {
            assertTrue(description.contains(slot.wireName()),
                    "the schema must advertise " + slot.wireName() + ": " + description);
            seen.set(null);
            TianshuTools.clearRateLimits();
            JsonObject result = envelope("{\"body\":\"body-1\",\"slot\":1,\"target\":\""
                    + slot.wireName() + "\"}", ToolCaller.player("p", true));
            assertTrue(result.get("ok").getAsBoolean(),
                    slot.wireName() + " must be accepted, got " + result);
            assertEquals(slot, seen.get(), "the port must see " + slot.wireName());
        }
    }
}