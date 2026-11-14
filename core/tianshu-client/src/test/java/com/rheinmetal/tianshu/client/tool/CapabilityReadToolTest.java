package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.capability.CapabilityBinding;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The agent's bridge to non-tool capabilities: listing, reading, and refusing to re-execute a tool. */
class CapabilityReadToolTest {

    private static final CapabilityId SKILL = CapabilityId.of("test", "skill");

    @BeforeEach
    void setUp() {
        TianshuTools.register(new CapabilityReadTool());
        CapabilityCatalog.register(new CapabilityDescriptor(SKILL, CapabilityKind.SKILL, CapabilityBinding.LOCAL,
                        "a test skill", ToolSide.CLIENT, ToolPermission.EVERYONE, "test", new JsonObject()),
                (context, args) -> {
                    JsonObject envelope = new JsonObject();
                    envelope.addProperty("ok", true);
                    envelope.addProperty("text", "skill body");
                    return CompletableFuture.completedFuture(envelope);
                });
    }

    @AfterEach
    void clean() {
        TianshuTools.unregister(CapabilityReadTool.ID);
        CapabilityCatalog.unregister(SKILL);
    }

    @Test
    void anEmptyIdListsWhatCanBeRead() throws Exception {
        JsonObject envelope = call("{}");
        String text = envelope.getAsJsonObject("result").get("text").getAsString();
        assertTrue(text.contains("test:skill"));
    }

    @Test
    void anIdReturnsTheText() throws Exception {
        JsonObject envelope = call("{\"id\":\"test:skill\"}");
        assertEquals("skill body", envelope.getAsJsonObject("result").get("text").getAsString());
    }

    @Test
    void aToolIsRefusedInsteadOfExecutedAgain() throws Exception {
        JsonObject envelope = call("{\"id\":\"tianshu:capability.read\"}");
        assertEquals("INVALID_ARGS", envelope.get("code").getAsString());
    }

    private static JsonObject call(String json) throws Exception {
        return TianshuTools.callJson(CapabilityReadTool.ID, JsonParser.parseString(json),
                        ToolCaller.agent(true), ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);
    }
}
