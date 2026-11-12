package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.RecipeInfo;
import com.rheinmetal.tianshu.api.game.RecipeQuery;
import com.rheinmetal.tianshu.api.game.RecipeQueryPort;
import com.rheinmetal.tianshu.api.game.RecipeQueryResult;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolHandle;
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

/**
 * The recipe tool owns the contract (id, clamp, JSON shape, failure codes) while the platform owns the lookup.
 * These tests exercise that split with a fake port, no game required.
 */
class RecipeQueryToolTest {

    private final AtomicReference<RecipeQuery> lastQuery = new AtomicReference<>();

    @BeforeEach
    void registerTools() {
        ClientTools.register();
    }

    @AfterEach
    void clean() {
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
    }

    @Test
    void returnsRecipesFromThePortAndKeepsTheCallerLimit() throws Exception {
        TianshuRuntimePorts.register(RecipeQueryPort.class, query -> {
            lastQuery.set(query);
            return CompletableFuture.completedFuture(List.of(
                    new RecipeInfo("minecraft:iron_pickaxe", "minecraft:crafting_shaped",
                            List.of("minecraft:iron_ingot", "minecraft:stick"), "minecraft:iron_pickaxe")));
        });

        JsonObject envelope = TianshuTools.callJson(RecipeQueryTool.ID,
                        JsonParser.parseString("{\"outputItemId\":\"minecraft:iron_pickaxe\",\"limit\":3}"),
                        ToolCaller.agent(false))
                .get(2, TimeUnit.SECONDS);

        assertTrue(envelope.get("ok").getAsBoolean());
        assertEquals(1, envelope.getAsJsonObject("result").getAsJsonArray("recipes").size());
        assertEquals(3, lastQuery.get().limit());
        assertEquals("minecraft:iron_pickaxe", lastQuery.get().outputItemId());
    }

    @Test
    void clampsTheLimitIntoTheAdvertisedRange() throws Exception {
        TianshuRuntimePorts.register(RecipeQueryPort.class, query -> {
            lastQuery.set(query);
            return CompletableFuture.completedFuture(List.of());
        });

        TianshuTools.callJson(RecipeQueryTool.ID,
                        JsonParser.parseString("{\"outputItemId\":\"minecraft:stick\",\"limit\":10000}"),
                        ToolCaller.agent(false))
                .get(2, TimeUnit.SECONDS);
        assertEquals(RecipeQueryTool.MAX_LIMIT, lastQuery.get().limit());

        TianshuTools.callJson(RecipeQueryTool.ID,
                        JsonParser.parseString("{\"outputItemId\":\"minecraft:stick\",\"limit\":0}"),
                        ToolCaller.agent(false))
                .get(2, TimeUnit.SECONDS);
        assertEquals(1, lastQuery.get().limit());
    }

    @Test
    void aTypedHandleReturnsTheSameData() throws Exception {
        TianshuRuntimePorts.register(RecipeQueryPort.class, query -> CompletableFuture.completedFuture(
                List.of(new RecipeInfo("r", "t", List.of("a"), "minecraft:stick"))));

        ToolHandle<RecipeQuery, RecipeQueryResult> handle =
                TianshuTools.handle(RecipeQueryTool.ID, RecipeQuery.class, RecipeQueryResult.class);
        RecipeQueryResult result = handle.call(new RecipeQuery("minecraft:stick", 5), ToolCaller.mod("m"))
                .get(2, TimeUnit.SECONDS);
        assertEquals(1, result.recipes().size());
    }

    @Test
    void withoutAPortTheToolReportsUnavailable() throws Exception {
        JsonObject envelope = TianshuTools.callJson(RecipeQueryTool.ID,
                        JsonParser.parseString("{\"outputItemId\":\"minecraft:stick\",\"limit\":1}"),
                        ToolCaller.agent(false))
                .get(2, TimeUnit.SECONDS);
        assertFalse(envelope.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", envelope.get("code").getAsString());
    }

    @Test
    void ablankItemIdIsInvalidArguments() throws Exception {
        JsonObject envelope = TianshuTools.callJson(RecipeQueryTool.ID,
                        JsonParser.parseString("{\"outputItemId\":\"   \",\"limit\":1}"),
                        ToolCaller.agent(false))
                .get(2, TimeUnit.SECONDS);
        assertFalse(envelope.get("ok").getAsBoolean());
        assertEquals("INVALID_ARGS", envelope.get("code").getAsString());
    }

    @Test
    void theToolIsAdvertisedAsALocalQueryWithASchema() {
        assertTrue(TianshuTools.catalog(ToolCaller.agent(false)).stream()
                .anyMatch(spec -> spec.id().equals(RecipeQueryTool.ID)
                        && spec.enabled()
                        && spec.argsSchema() != null));
    }
}