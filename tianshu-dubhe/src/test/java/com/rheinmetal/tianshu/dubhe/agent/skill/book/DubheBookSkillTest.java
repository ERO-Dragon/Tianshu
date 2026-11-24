package com.rheinmetal.tianshu.dubhe.agent.skill.book;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.InstalledModIds;
import com.rheinmetal.tianshu.api.assembly.ModResources;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkill;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillContext;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheBookSkillTest {

    private static final DubhePromptLanguageProvider LANGUAGE =
            DubhePromptLanguageProvider.fixed(DubhePromptLanguage.ZH_CN);

    @AfterEach
    void clearPorts() {
        TianshuRuntimePorts.clear();
    }

    @Test
    void answersFromTheInstalledBookAndNamesItsMod() {
        register(FakeModResources.builder()
                .put("testmod", "assets/testmod/patchouli_books/guide/book.json", "{}")
                .put("testmod", "assets/testmod/patchouli_books/guide/en_us/entries/mana.json",
                        "{\"name\":\"Mana Pool\",\"pages\":[{\"type\":\"text\",\"text\":\"stores mana\"}]}")
                .build());

        JsonObject result = invoke(query("mana"));

        assertEquals("OK", result.get("outcome").getAsString());
        JsonObject match = result.getAsJsonArray("matches").get(0).getAsJsonObject();
        assertEquals("testmod", match.get("mod").getAsString());
        assertEquals("Mana Pool", match.get("title").getAsString());
        assertEquals("stores mana", match.get("text").getAsString());
    }

    @Test
    void reportsNoBooksRatherThanFailingWhenNothingShipsAGuide() {
        register(FakeModResources.builder().build());

        JsonObject result = invoke(query("mana"));

        assertEquals("EMPTY", result.get("outcome").getAsString());
        assertEquals("NO_BOOKS", result.get("reason").getAsString());
    }

    @Test
    void reportsNoMatchWhenTheTermIsNotInAnyBook() {
        register(FakeModResources.builder()
                .put("testmod", "assets/testmod/patchouli_books/guide/book.json", "{}")
                .put("testmod", "assets/testmod/patchouli_books/guide/en_us/entries/mana.json",
                        "{\"name\":\"Mana Pool\",\"pages\":[{\"type\":\"text\",\"text\":\"stores mana\"}]}")
                .build());

        JsonObject result = invoke(query("netherite"));

        assertEquals("EMPTY", result.get("outcome").getAsString());
        assertEquals("NO_MATCH", result.get("reason").getAsString());
    }

    @Test
    void rejectsAnEmptyQuery() {
        register(FakeModResources.builder().build());

        JsonObject result = invoke(query("  "));

        assertEquals("FAILED", result.get("outcome").getAsString());
        assertEquals("QUERY_REQUIRED", result.get("reason").getAsString());
    }

    @Test
    void capsTheBodyItHandsBack() {
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < 400; index++) {
            body.append("mana ");
        }
        register(FakeModResources.builder()
                .put("testmod", "assets/testmod/patchouli_books/guide/book.json", "{}")
                .put("testmod", "assets/testmod/patchouli_books/guide/en_us/entries/mana.json",
                        "{\"name\":\"Mana Pool\",\"pages\":[{\"type\":\"text\",\"text\":\"" + body + "\"}]}")
                .build());

        String text = invoke(query("mana")).getAsJsonArray("matches").get(0)
                .getAsJsonObject().get("text").getAsString();

        assertTrue(text.length() <= 1800, "one answer must not flood the agent's context: " + text.length());
        assertTrue(text.endsWith("\u2026"), "a cut body is marked as cut");
    }

    private static void register(ModResources resources) {
        TianshuRuntimePorts.register(InstalledModIds.class, () -> List.of("testmod"));
        TianshuRuntimePorts.register(ModResources.class, resources);
    }

    private static JsonObject query(String query) {
        JsonObject args = new JsonObject();
        args.addProperty("query", query);
        return args;
    }

    private static JsonObject invoke(JsonObject args) {
        DubheSkill skill = DubheBookSkill.skill(LANGUAGE);
        return skill.executor().execute(new DubheSkillContext(null, null, null), args).join();
    }
}