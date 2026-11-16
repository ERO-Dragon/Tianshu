package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.CraftPort;
import com.rheinmetal.tianshu.api.game.CraftRequest;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.game.CraftResult;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftRecipeToolTest {

    @BeforeEach
    void clearThrottle() {
        TianshuTools.clearRateLimits();
    }

    @BeforeEach
    void registerDeclaration() {
        ClientTools.register();
    }

    @AfterEach
    void clean() {
        TianshuTools.unregister(CraftRecipeTool.ID);
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
        EntityActors.clearForTests();
    }

    private static JsonObject envelope(String json, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(CraftRecipeTool.ID, JsonParser.parseString(json), caller, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
    }

    @Test
    void craftingIsOffUntilAnOperatorEnablesIt() throws Exception {
        TianshuRuntimePorts.register(CraftPort.class, (playerId, request) -> CompletableFuture.completedFuture(
                new CraftResult("minecraft:iron_pickaxe", 1, true, "crafted")));
        TianshuTools.register(new CraftRecipeTool());

        JsonObject disabled = envelope("{\"recipeId\":\"minecraft:iron_pickaxe\"}", ToolCaller.player("p", true));
        assertFalse(disabled.get("ok").getAsBoolean());
        assertEquals("DISABLED", disabled.get("code").getAsString());

        TianshuTools.setEnabled(CraftRecipeTool.ID, true);
        JsonObject crafted = envelope("{\"recipeId\":\"minecraft:iron_pickaxe\"}", ToolCaller.player("p", true));
        assertTrue(crafted.get("ok").getAsBoolean());
        assertEquals("minecraft:iron_pickaxe",
                crafted.getAsJsonObject("result").get("itemId").getAsString());
    }

    @Test
    void aBodyCallerReachesTheActorAwarePort() throws Exception {
        java.util.concurrent.atomic.AtomicReference<ActorRef> seen = new java.util.concurrent.atomic.AtomicReference<>();
        TianshuRuntimePorts.register(CraftPort.class, new CraftPort() {
            @Override
            public CompletableFuture<CraftResult> craft(String playerId, CraftRequest request) {
                throw new AssertionError("body call must not fall back to the player path");
            }

            @Override
            public CompletableFuture<CraftResult> craft(ActorRef actor, CraftRequest request) {
                seen.set(actor);
                return CompletableFuture.completedFuture(new CraftResult("minecraft:stick", 4, true, "crafted"));
            }
        });
        TianshuTools.register(new CraftRecipeTool());
        TianshuTools.setEnabled(CraftRecipeTool.ID, true);
        TestBodies.register("body-1", EntityCapability.INVENTORY);

        JsonObject envelope = envelope("{\"recipeId\":\"minecraft:stick\"}", ToolCaller.body("body-1", true));
        assertTrue(envelope.get("ok").getAsBoolean());
        assertEquals(ActorRef.Kind.BODY, seen.get().kind());
        assertEquals("body-1", seen.get().id());
    }

    @Test
    void aBodyWithoutAnInventoryCannotCraft() throws Exception {
        TianshuTools.register(new CraftRecipeTool());
        TianshuTools.setEnabled(CraftRecipeTool.ID, true);
        TestBodies.register("float", EntityCapability.MOVE, EntityCapability.LOOK);

        JsonObject refused = envelope("{\"recipeId\":\"minecraft:stick\"}", ToolCaller.body("float", true));
        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNSUPPORTED_ACTOR", refused.get("code").getAsString());
    }

    @Test
    void anUnknownBodyIsUnavailable() throws Exception {
        TianshuTools.register(new CraftRecipeTool());
        TianshuTools.setEnabled(CraftRecipeTool.ID, true);

        JsonObject refused = envelope("{\"recipeId\":\"minecraft:stick\"}", ToolCaller.body("gone", true));
        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", refused.get("code").getAsString());
    }

    @Test
    void aRefusedCraftIsAFailedCall() throws Exception {
        TianshuRuntimePorts.register(CraftPort.class, (playerId, request) -> CompletableFuture.completedFuture(
                new CraftResult("", 0, false, "a crafting table is required nearby")));
        TianshuTools.register(new CraftRecipeTool());
        TianshuTools.setEnabled(CraftRecipeTool.ID, true);

        JsonObject refused = envelope("{\"recipeId\":\"minecraft:stick\"}", ToolCaller.player("p", true));
        assertFalse(refused.get("ok").getAsBoolean());
        assertEquals("FAILED", refused.get("code").getAsString());
        assertTrue(refused.get("message").getAsString().contains("crafting table"));
    }

    @Test
    void onlyAPlayerMayCraft() throws Exception {
        TianshuTools.register(new CraftRecipeTool());
        TianshuTools.setEnabled(CraftRecipeTool.ID, true);

        JsonObject denied = envelope("{\"recipeId\":\"minecraft:iron_pickaxe\"}", ToolCaller.agent(false));
        assertFalse(denied.get("ok").getAsBoolean());
        assertEquals("NO_PERMISSION", denied.get("code").getAsString());
    }

    @Test
    void aBlankRecipeIdIsInvalid() throws Exception {
        TianshuTools.register(new CraftRecipeTool());
        TianshuTools.setEnabled(CraftRecipeTool.ID, true);

        JsonObject invalid = envelope("{\"recipeId\":\"  \"}", ToolCaller.player("p", true));
        assertFalse(invalid.get("ok").getAsBoolean());
        assertEquals("INVALID_ARGS", invalid.get("code").getAsString());
    }

    @Test
    void withoutAPortTheToolReportsUnavailable() throws Exception {
        TianshuTools.register(new CraftRecipeTool());
        TianshuTools.setEnabled(CraftRecipeTool.ID, true);

        JsonObject result = envelope("{\"recipeId\":\"minecraft:stick\"}", ToolCaller.player("p", true));
        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", result.get("code").getAsString());
    }
}