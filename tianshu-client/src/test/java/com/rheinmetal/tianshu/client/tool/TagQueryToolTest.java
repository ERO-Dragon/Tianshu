package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.TagKind;
import com.rheinmetal.tianshu.api.game.TagQuery;
import com.rheinmetal.tianshu.api.game.TagQueryPort;
import com.rheinmetal.tianshu.api.game.TagQueryResult;
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

class TagQueryToolTest {

    private final AtomicReference<TagQuery> lastQuery = new AtomicReference<>();

    @BeforeEach
    void registerTools() {
        ClientTools.register();
        TianshuRuntimePorts.register(TagQueryPort.class, query -> {
            lastQuery.set(query);
            if (query.itemId() != null) {
                return CompletableFuture.completedFuture(new TagQueryResult(List.of("minecraft:planks")));
            }
            return CompletableFuture.completedFuture(new TagQueryResult(List.of("minecraft:oak_planks")));
        });
    }

    @AfterEach
    void clean() {
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
    }

    @Test
    void anItemIdListsItsTags() throws Exception {
        JsonObject envelope = TianshuTools.callJson(TagQueryTool.ID,
                        JsonParser.parseString("{\"kind\":\"item\",\"itemId\":\"minecraft:oak_planks\"}"),
                        ToolCaller.agent(false))
                .get(2, TimeUnit.SECONDS);
        assertTrue(envelope.get("ok").getAsBoolean());
        assertEquals("minecraft:oak_planks", lastQuery.get().itemId());
        assertEquals(TagKind.ITEM, lastQuery.get().kind());
        assertTrue(envelope.getAsJsonObject("result").getAsJsonArray("entries").size() == 1);
    }

    @Test
    void aTagIdListsItsEntries() throws Exception {
        JsonObject envelope = TianshuTools.callJson(TagQueryTool.ID,
                        JsonParser.parseString("{\"kind\":\"block\",\"tagId\":\"minecraft:planks\"}"),
                        ToolCaller.agent(false))
                .get(2, TimeUnit.SECONDS);
        assertTrue(envelope.get("ok").getAsBoolean());
        assertEquals(TagKind.BLOCK, lastQuery.get().kind());
        assertEquals("minecraft:planks", lastQuery.get().tagId());
    }

    @Test
    void exactlyOneOfItemAndTagIsRequired() throws Exception {
        assertInvalid("{\"kind\":\"item\"}");
        assertInvalid("{\"kind\":\"item\",\"itemId\":\"minecraft:stick\",\"tagId\":\"minecraft:planks\"}");
        assertInvalid("{\"kind\":\"ore\",\"itemId\":\"minecraft:stick\"}");
    }

    @Test
    void withoutAPortTheToolReportsUnavailable() throws Exception {
        TianshuRuntimePorts.clear();
        JsonObject envelope = TianshuTools.callJson(TagQueryTool.ID,
                        JsonParser.parseString("{\"kind\":\"item\",\"itemId\":\"minecraft:stick\"}"),
                        ToolCaller.agent(false))
                .get(2, TimeUnit.SECONDS);
        assertFalse(envelope.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", envelope.get("code").getAsString());
    }

    @Test
    void aTypedHandleReturnsTheSameData() throws Exception {
        ToolHandle<TagQueryArgs, TagQueryResult> handle =
                TianshuTools.handle(TagQueryTool.ID, TagQueryArgs.class, TagQueryResult.class);
        TagQueryArgs args = new TagQueryArgs();
        args.kind = "item";
        args.itemId = "minecraft:oak_planks";
        TagQueryResult result = handle.call(args, ToolCaller.mod("m")).get(2, TimeUnit.SECONDS);
        assertEquals(List.of("minecraft:planks"), result.entries());
    }

    private void assertInvalid(String json) throws Exception {
        JsonObject envelope = TianshuTools.callJson(TagQueryTool.ID, JsonParser.parseString(json),
                ToolCaller.agent(false)).get(2, TimeUnit.SECONDS);
        assertFalse(envelope.get("ok").getAsBoolean(), json);
        assertEquals("INVALID_ARGS", envelope.get("code").getAsString(), json);
    }
}