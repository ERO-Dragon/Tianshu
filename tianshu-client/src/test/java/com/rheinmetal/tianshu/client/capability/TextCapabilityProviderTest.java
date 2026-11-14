package com.rheinmetal.tianshu.client.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** File-backed resources and prompts: flat files, standard front matter, paging and template rendering. */
class TextCapabilityProviderTest {

    @TempDir
    Path root;

    private TextCapabilityProvider provider;

    @AfterEach
    void clean() {
        if (provider != null) {
            provider.unload();
        }
    }

    @Test
    void aResourceIsListedAndReadByDemand() throws Exception {
        write("guide.md", "guide", "A small guide", "guide body");
        provider = TextCapabilityProvider.install(CapabilityKind.RESOURCE, "user", root);

        CapabilityId id = CapabilityId.of("user", "guide");
        assertEquals(CapabilityKind.RESOURCE, CapabilityCatalog.find(id).orElseThrow().kind());
        assertEquals("guide body", text(CapabilityCatalog.invoke(id, new JsonObject(),
                ToolCaller.agent(true), ToolSide.CLIENT).get(2, TimeUnit.SECONDS)));
    }

    @Test
    void aLongResourceIsPaged() throws Exception {
        write("long.md", "long", "A long guide", "x".repeat(13_000));
        provider = TextCapabilityProvider.install(CapabilityKind.RESOURCE, "user", root);
        JsonObject page = new JsonObject();
        page.addProperty("page", 2);

        String second = text(CapabilityCatalog.invoke(CapabilityId.of("user", "long"), page,
                ToolCaller.agent(true), ToolSide.CLIENT).get(2, TimeUnit.SECONDS));
        assertTrue(second.contains("[page 2/3"));
    }

    @Test
    void aPromptRendersItsPlaceholders() throws Exception {
        write("greet.md", "greet", "Greets a player", "Hello {{name}}, welcome to {{world}}.");
        provider = TextCapabilityProvider.install(CapabilityKind.PROMPT, "user", root);

        JsonObject args = new JsonObject();
        args.addProperty("name", "Alex");
        String rendered = text(CapabilityCatalog.invoke(CapabilityId.of("user", "greet"), args,
                ToolCaller.agent(true), ToolSide.CLIENT).get(2, TimeUnit.SECONDS));
        assertEquals("Hello Alex, welcome to {{world}}.", rendered);
    }

    @Test
    void onlyResourceAndPromptAreAccepted() {
        assertThrows(IllegalArgumentException.class,
                () -> TextCapabilityProvider.install(CapabilityKind.SKILL, "user", root));
    }

    @Test
    void aNameThatDoesNotMatchTheFileIsSkipped() throws Exception {
        write("wrong.md", "other", "nope", "body");
        provider = TextCapabilityProvider.install(CapabilityKind.RESOURCE, "user", root);

        assertFalse(provider.problems().isEmpty());
        assertTrue(CapabilityCatalog.find(CapabilityId.of("user", "other")).isEmpty());
    }

    private static String text(JsonObject envelope) {
        assertTrue(envelope.get("ok").getAsBoolean(), envelope.toString());
        return envelope.get("text").getAsString();
    }

    private void write(String file, String name, String description, String body) throws Exception {
        Files.writeString(root.resolve(file),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body,
                StandardCharsets.UTF_8);
    }
}