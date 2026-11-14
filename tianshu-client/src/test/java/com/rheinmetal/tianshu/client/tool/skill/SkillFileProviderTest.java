package com.rheinmetal.tianshu.client.tool.skill;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityExposure;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Common SKILL.md format, namespaced ids, duplicate detection and progressive disclosure. */
class SkillFileProviderTest {

    @TempDir
    Path root;

    private SkillFileProvider provider;

    @AfterEach
    void clean() {
        if (provider != null) {
            provider.unload();
        }
    }

    @Test
    void aSkillIsIndexedByNameAndReadByDemand() throws Exception {
        write("alpha", "alpha", "A test skill", "# Alpha\nbody line");
        writeFile("alpha", "references/detail.md", "detail body");
        provider = SkillFileProvider.install("user", root);

        CapabilityId id = CapabilityId.of("user", "alpha");
        assertEquals(CapabilityKind.SKILL, CapabilityCatalog.find(id).orElseThrow().kind());
        assertEquals("A test skill", CapabilityCatalog.find(id).orElseThrow().description());
        assertTrue(CapabilityExposure.describe(CapabilityExposure.exposed(ToolCaller.agent(true), ToolSide.CLIENT))
                .contains("user:alpha"));

        assertEquals("# Alpha\nbody line", text(invoke(id, new JsonObject())));
        JsonObject reference = new JsonObject();
        reference.addProperty("file", "references/detail.md");
        assertEquals("detail body", text(invoke(id, reference)));
    }

    @Test
    void longTextIsPagedAndTheFooterPointsToTheNextPage() throws Exception {
        String body = "x".repeat(13_000);
        write("long", "long", "A long skill", body);
        provider = SkillFileProvider.install("user", root);
        CapabilityId id = CapabilityId.of("user", "long");

        String first = text(invoke(id, new JsonObject()));
        assertTrue(first.contains("[page 1/3"));

        JsonObject second = new JsonObject();
        second.addProperty("page", 2);
        String pageTwo = text(invoke(id, second));
        assertTrue(pageTwo.contains("[page 2/3"));

        JsonObject beyond = new JsonObject();
        beyond.addProperty("page", 9);
        JsonObject rejected = invoke(id, beyond);
        assertFalse(rejected.get("ok").getAsBoolean());
    }

    @Test
    void referencesCannotEscapeTheSkillDirectory() throws Exception {
        write("safe", "safe", "A safe skill", "body");
        provider = SkillFileProvider.install("user", root);
        JsonObject traversal = new JsonObject();
        traversal.addProperty("file", "../safe/SKILL.md");

        assertFalse(invoke(CapabilityId.of("user", "safe"), traversal).get("ok").getAsBoolean());
    }

    @Test
    void aDuplicateIdIsReportedAndTheFirstSkillStays() throws Exception {
        write("alpha", "alpha", "first", "one");
        provider = SkillFileProvider.install("user", root);
        CapabilityId id = CapabilityId.of("user", "alpha");
        assertTrue(CapabilityCatalog.find(id).isPresent());

        SkillFileProvider second = SkillFileProvider.install("user", root);
        assertFalse(second.problems().isEmpty(), "a second provider with the same namespace must report the clash");
        assertEquals("first", CapabilityCatalog.find(id).orElseThrow().description());
        second.unload();
    }

    @Test
    void malformedNamesAreSkippedWithAProblem() throws Exception {
        write("bad", "Bad Name", "nope", "body");
        write("mismatch", "other", "nope", "body");
        provider = SkillFileProvider.install("user", root);

        assertEquals(2, provider.problems().size());
        assertTrue(CapabilityCatalog.find(CapabilityId.of("user", "bad")).isEmpty());
        assertTrue(CapabilityCatalog.find(CapabilityId.of("user", "other")).isEmpty());
    }

    private JsonObject invoke(CapabilityId id, JsonObject args) throws Exception {
        return CapabilityCatalog.invoke(id, args, ToolCaller.agent(true), ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);
    }

    private static String text(JsonObject envelope) {
        assertTrue(envelope.get("ok").getAsBoolean(), envelope.toString());
        return envelope.get("text").getAsString();
    }

    private void write(String directory, String name, String description, String body) throws Exception {
        Path skill = root.resolve(directory);
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body,
                StandardCharsets.UTF_8);
    }

    private void writeFile(String directory, String relative, String body) throws Exception {
        Path file = root.resolve(directory).resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }
}