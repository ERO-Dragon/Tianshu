package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.capability.CapabilityBinding;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.capability.skill.SkillFileProvider;
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

    private SkillFileProvider skills;

    @BeforeEach
    void setUp() {
        // A tool's document lives in the references of the skill that ships it, so the skill providers have to be up.
        skills = SkillFileProvider.installClasspath("tianshu", "minecraft", "tianshu/skills");
        TianshuTools.registerBuiltin(new CapabilityReadTool());
        // The row below belongs to a module, so the consumer half of the sharing declaration is needed for the
        // listing case: an undeclared subscription sees the base only.
        com.rheinmetal.tianshu.api.capability.CapabilitySubscriptions.declare("tianshu",
                com.rheinmetal.tianshu.api.capability.CapabilitySubscription.everythingFrom("test"));
        CapabilityCatalog.register(new CapabilityDescriptor(SKILL, CapabilityKind.SKILL, CapabilityBinding.LOCAL,
                        "a test skill", ToolSide.CLIENT, ToolPermission.EVERYONE, "test", shared()),
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
        com.rheinmetal.tianshu.api.capability.CapabilitySubscriptions.clear();
        skills.unload();
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
    void readingAToolReturnsItsDocumentInsteadOfRunningIt() throws Exception {
        var tool = new ContainerTransferTool();
        TianshuTools.registerBuiltin(tool);
        try {
            JsonObject envelope = call("{\"id\":\"minecraft:container.transfer\"}");
            assertTrue(envelope.get("ok").getAsBoolean(), envelope.toString());
            assertTrue(envelope.getAsJsonObject("result").get("text").getAsString()
                    .contains("# minecraft:container.transfer"));
        } finally {
            TianshuTools.unregister(tool);
        }
    }

    @Test
    void anUnknownToolIsStillRefused() throws Exception {
        JsonObject envelope = call("{\"id\":\"tianshu:no.such.tool\"}");
        assertEquals("UNKNOWN_TOOL", envelope.get("code").getAsString());
    }

    private static JsonObject call(String json) throws Exception {
        return TianshuTools.callJson(CapabilityReadTool.ID, JsonParser.parseString(json),
                        ToolCaller.agent(true), ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);
    }

    /** The provider half of the sharing declaration: this row is opened to everyone. */
    private static com.google.gson.JsonObject shared() {
        com.google.gson.JsonObject attributes = new com.google.gson.JsonObject();
        attributes.addProperty("share", "all");
        return attributes;
    }
}
