package com.rheinmetal.tianshu.client.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.skill.SkillFileProvider;
import com.rheinmetal.tianshu.api.capability.skill.SkillReferences;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.client.tool.BodyMineTool;
import com.rheinmetal.tianshu.client.tool.CapabilityReadTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        skills = SkillFileProvider.installClasspath("tianshu", "tianshu/skills");
    }

    @AfterEach
    void clean() {
        TianshuTools.unregister(BodyMineTool.ID);
        TianshuTools.unregister(CapabilityReadTool.ID);
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
    void aToolWithoutADocumentHasNone() {
        assertTrue(SkillReferences.of(CapabilityId.parse("tianshu:no.such.tool")).isEmpty());
        assertEquals("references/no.such.tool.md", SkillReferences.pathOf(CapabilityId.parse("tianshu:no.such.tool")));
    }

    @Test
    void readingAToolReturnsItsOwnDocument() throws Exception {
        TianshuTools.register(new BodyMineTool());
        TianshuTools.register(readTool);

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

    private static JsonObject request(String id) {
        JsonObject args = new JsonObject();
        args.addProperty("id", id);
        return args;
    }
}