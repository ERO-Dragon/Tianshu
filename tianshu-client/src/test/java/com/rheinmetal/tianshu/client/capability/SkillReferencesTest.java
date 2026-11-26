package com.rheinmetal.tianshu.client.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilitySubscription;
import com.rheinmetal.tianshu.api.capability.CapabilitySubscriptions;
import com.rheinmetal.tianshu.api.capability.skill.SkillFileProvider;
import com.rheinmetal.tianshu.api.capability.skill.SkillReferences;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.client.tool.BodyMineTool;
import com.rheinmetal.tianshu.client.tool.CapabilityReadTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One tool, one document: the skill that ships the tool ships its document too, and the file is found from the id.
 *
 * <p>This replaced a coverage index that mapped skills to capability lists. The index could disagree with reality (a
 * typo documented nothing, two skills could claim one tool, and a document about thirteen tools was handed whole to
 * someone who had two). Deriving the path from the id removes all three at once.</p>
 *
 * <p>Finding it goes through the skill providers, not the class loader, so a mod that registers its own skill folder
 * documents its own tools the same way the base documents vanilla ones.</p>
 */
class SkillReferencesTest {

    private final CapabilityReadTool readTool = new CapabilityReadTool();

    private SkillFileProvider skills;

    @BeforeEach
    void installBuiltInSkills() {
        skills = SkillFileProvider.installClasspath("tianshu", "minecraft", "tianshu/skills");
    }

    @AfterEach
    void clean() {
        TianshuTools.unregister(BodyMineTool.ID);
        TianshuTools.unregister(CapabilityReadTool.ID);
        CapabilitySubscriptions.clear();
        skills.unload();
    }

    @Test
    void theDocumentIsFoundFromTheToolId() {
        var document = SkillReferences.of(CapabilityId.parse("minecraft:entity.mine"));

        assertTrue(document.isPresent(), "minecraft:entity.mine reads references/entity.mine.md");
        assertEquals(CapabilityId.parse("tianshu:entity-tools"), document.get().skill());
        assertEquals("references/entity.mine.md", document.get().path());
    }

    @Test
    void sameNamedToolsReadTheirOwnManuals(@TempDir Path root) throws Exception {
        CapabilityId otherMine = CapabilityId.parse("other:entity.mine");
        assertTrue(SkillReferences.of(otherMine).isEmpty());
        Path skill = root.resolve("mining");
        Files.createDirectories(skill.resolve("references"));
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: mining\ndescription: Mining tools\n---\n");
        Files.writeString(skill.resolve("references/entity.mine.md"), "other manual");
        SkillFileProvider other = SkillFileProvider.install("other", root);
        try {
            assertEquals("other manual", SkillReferences.of(otherMine).orElseThrow().text());
            assertEquals(CapabilityId.parse("tianshu:entity-tools"),
                    SkillReferences.of(CapabilityId.parse("minecraft:entity.mine")).orElseThrow().skill());
        } finally {
            other.unload();
        }
        assertTrue(SkillReferences.of(otherMine).isEmpty());
    }

    @Test
    void aToolWithoutADocumentHasNone() {
        assertTrue(SkillReferences.of(CapabilityId.parse("tianshu:no.such.tool")).isEmpty());
        assertEquals("references/no.such.tool.md", SkillReferences.pathOf(CapabilityId.parse("tianshu:no.such.tool")));
    }

    @Test
    void readingAToolReturnsItsOwnDocument() throws Exception {
        TianshuTools.registerBuiltin(new BodyMineTool());
        TianshuTools.registerBuiltin(readTool);

        JsonObject answer = com.rheinmetal.tianshu.api.capability.CapabilityCatalog.invoke(
                        CapabilityId.parse(CapabilityReadTool.ID.toString()),
                        request("minecraft:entity.mine"), ToolCaller.agent(true), ToolSide.CLIENT)
                .thenCompose(envelope -> {
                    assertTrue(envelope.get("ok").getAsBoolean(), envelope.toString());
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            envelope.getAsJsonObject("result"));
                }).get(5, TimeUnit.SECONDS);

        assertTrue(answer.get("text").getAsString().contains("entity.mine"), answer.toString());
    }

    @Test
    void theReaderUsesTheTargetsSubscriptionForBothIdAndFileReads() {
        TianshuTools.registerBuiltin(new BodyMineTool());
        TianshuTools.registerBuiltin(readTool);
        ToolCaller caller = ToolCaller.agent("changsui", false, ToolAutonomy.CONSERVATIVE);
        CapabilityId skill = CapabilityId.parse("tianshu:entity-tools");
        CapabilityId tool = CapabilityId.parse("minecraft:entity.mine");
        JsonObject file = request(skill.toString());
        file.addProperty("file", SkillReferences.pathOf(tool));

        assertEquals("UNKNOWN_TOOL", read(caller, request(skill.toString())).get("code").getAsString());
        CapabilitySubscriptions.declare(caller.id(), CapabilitySubscription.only(skill));
        assertTrue(read(caller, request(skill.toString())).get("ok").getAsBoolean());
        assertEquals("UNKNOWN_TOOL", read(caller, request(tool.toString())).get("code").getAsString());
        assertFalse(read(caller, file).get("ok").getAsBoolean());

        CapabilitySubscriptions.declare(caller.id(), CapabilitySubscription.only(tool));
        assertTrue(read(caller, request(tool.toString())).get("ok").getAsBoolean());
        assertEquals("UNKNOWN_TOOL", read(caller, request(skill.toString())).get("code").getAsString());

        CapabilitySubscriptions.declare(caller.id(), CapabilitySubscription.only(skill, tool));
        assertTrue(read(caller, file).get("ok").getAsBoolean());
    }

    private static JsonObject read(ToolCaller caller, JsonObject request) {
        return TianshuTools.callJson(CapabilityReadTool.ID, request, caller, ToolSide.CLIENT).join();
    }

    private static JsonObject request(String id) {
        JsonObject args = new JsonObject();
        args.addProperty("id", id);
        return args;
    }
}