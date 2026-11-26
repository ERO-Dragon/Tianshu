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
import com.rheinmetal.tianshu.api.entity.PickedItem;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyPickupToolTest {

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
        TianshuTools.unregister(BodyPickupTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
        EntityActors.clearForTests();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(BodyPickupTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    private static void enable() {
        TianshuTools.register(new BodyPickupTool());
        TianshuTools.setEnabled(BodyPickupTool.ID, true);
    }

    @Test
    void aBodyWithoutAnInventoryIsRefusedBeforeThePortIsTouched() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        TianshuRuntimePorts.register(EntityItemPort.class, new EntityItemPort() {
            @Override
            public CompletableFuture<PickupResult> pickup(ActorRef actor, int radius) {
                reached.set(true);
                return CompletableFuture.completedFuture(new PickupResult(List.of()));
            }

            @Override
            public CompletableFuture<DropResult> drop(ActorRef actor, int slot, int count) {
                throw new AssertionError("this test must not drop");
            }

            @Override
            public CompletableFuture<EquipResult> equip(ActorRef actor, EquipRequest request) {
                throw new AssertionError("a pickup test must not equip");
            }
        });
        enable();
        TestBodies.register("float", EntityCapability.MOVE);

        JsonObject refused = envelope("{\"body\":\"float\"}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNSUPPORTED_ACTOR", refused.get("code").getAsString());
        assertFalse(reached.get());
    }

    @Test
    void aPickupReachesThePortAsTheNamedBody() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        TianshuRuntimePorts.register(EntityItemPort.class, new EntityItemPort() {
            @Override
            public CompletableFuture<PickupResult> pickup(ActorRef actor, int radius) {
                seen.set(actor);
                return CompletableFuture.completedFuture(
                        new PickupResult(List.of(new PickedItem("minecraft:oak_log", 3))));
            }

            @Override
            public CompletableFuture<DropResult> drop(ActorRef actor, int slot, int count) {
                throw new AssertionError("this test must not drop");
            }

            @Override
            public CompletableFuture<EquipResult> equip(ActorRef actor, EquipRequest request) {
                throw new AssertionError("a pickup test must not equip");
            }
        });
        enable();
        TestBodies.register("body-1", EntityCapability.INVENTORY);

        JsonObject result = envelope("{\"body\":\"body-1\"}", ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.BODY, seen.get().kind());
        assertEquals("minecraft:oak_log",
                result.getAsJsonObject("result").getAsJsonArray("items").get(0).getAsJsonObject()
                        .get("itemId").getAsString());
    }

    @Test
    void aPlayerWhoNamesNoBodyIsRefused() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        TianshuRuntimePorts.register(EntityItemPort.class, new EntityItemPort() {
            @Override
            public CompletableFuture<PickupResult> pickup(ActorRef actor, int radius) {
                seen.set(actor);
                return CompletableFuture.completedFuture(new PickupResult(List.of()));
            }

            @Override
            public CompletableFuture<DropResult> drop(ActorRef actor, int slot, int count) {
                throw new AssertionError("this test must not drop");
            }

            @Override
            public CompletableFuture<EquipResult> equip(ActorRef actor, EquipRequest request) {
                throw new AssertionError("a pickup test must not equip");
            }
        });
        enable();

        JsonObject refused = envelope("{}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("INVALID_ARGS", refused.get("code").getAsString());
        assertEquals("body.target.required", refused.get("message").getAsString());
    }

    @Test
    void anUnknownBodyIsUnavailable() throws Exception {
        enable();

        JsonObject refused = envelope("{\"body\":\"gone\"}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", refused.get("code").getAsString());
    }
}