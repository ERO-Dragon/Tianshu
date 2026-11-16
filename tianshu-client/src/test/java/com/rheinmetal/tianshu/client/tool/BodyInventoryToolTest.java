package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityQueryPort;
import com.rheinmetal.tianshu.api.entity.ScanResult;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.InventoryEntry;
import com.rheinmetal.tianshu.api.game.InventorySnapshot;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyInventoryToolTest {

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
        TianshuTools.unregister(BodyInventoryTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
        EntityActors.clearForTests();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(BodyInventoryTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    private static void enable() {
        TianshuTools.register(new BodyInventoryTool());
    }

    @Test
    void anInventoryReadReachesThePortAsTheNamedBody() throws Exception {
        AtomicReference<ActorRef> seen = new AtomicReference<>();
        TianshuRuntimePorts.register(EntityQueryPort.class, new EntityQueryPort() {
            @Override
            public CompletableFuture<ScanResult> scan(ActorRef actor, com.rheinmetal.tianshu.api.entity.ScanRequest request) {
                throw new AssertionError("inventory must not scan");
            }

            @Override
            public CompletableFuture<InventorySnapshot> inventory(ActorRef actor) {
                seen.set(actor);
                return CompletableFuture.completedFuture(
                        new InventorySnapshot(List.of(new InventoryEntry(0, "minecraft:stone", 12))));
            }
        });
        enable();
        TestBodies.register("body-1");

        JsonObject result = envelope("{\"body\":\"body-1\"}", ToolCaller.player("p", true));

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.BODY, seen.get().kind());
        assertEquals("minecraft:stone",
                result.getAsJsonObject("result").getAsJsonArray("slots").get(0).getAsJsonObject()
                        .get("itemId").getAsString());
    }

    @Test
    void anUnknownBodyIsUnavailable() throws Exception {
        enable();

        JsonObject refused = envelope("{\"body\":\"gone\"}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", refused.get("code").getAsString());
    }

    @Test
    void withoutAPortTheToolReportsUnavailable() throws Exception {
        enable();

        JsonObject refused = envelope("{\"body\":\"body-1\"}", ToolCaller.player("p", true));

        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", refused.get("code").getAsString());
    }
}