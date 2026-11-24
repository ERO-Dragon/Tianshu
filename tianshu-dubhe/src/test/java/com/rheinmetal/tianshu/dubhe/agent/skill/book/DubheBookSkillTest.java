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
        register(twoBooks(), "alpha", "beta");

        JsonObject result = call(arg("search", "query", "mana"));

        assertEquals("OK", result.get("outcome").getAsString());
        JsonObject match = result.getAsJsonArray("matches").get(0).getAsJsonObject();
        assertEquals("alpha", match.get("mod").getAsString());
        assertEquals("Mana Pool", match.get("title").getAsString());
        assertEquals("stores mana", match.get("text").getAsString());
    }

    @Test
    void listsWhichModsShipGuides() {
        register(twoBooks(), "alpha", "beta");

        JsonObject result = call(arg("list"));

        assertEquals("OK", result.get("outcome").getAsString());
        assertEquals(2, result.getAsJsonArray("mods").size());
        assertEquals("alpha", result.getAsJsonArray("mods").get(0).getAsJsonObject().get("mod").getAsString());
    }

    @Test
    void listsTheTitlesOfOneMod() {
        register(twoBooks(), "alpha", "beta");

        JsonObject result = call(arg("list", "mod", "beta"));

        assertEquals("OK", result.get("outcome").getAsString());
        assertEquals(1, result.getAsJsonArray("titles").size());
        assertEquals("Flowers", result.getAsJsonArray("titles").get(0).getAsString());
    }

    @Test
    void readsAnEntryByExactTitle() {
        register(twoBooks(), "alpha", "beta");

        JsonObject result = call(arg("read", "mod", "alpha", "title", "Mana Pool"));

        assertEquals("OK", result.get("outcome").getAsString());
        assertEquals("stores mana", result.get("text").getAsString());
        assertEquals("title", result.get("via").getAsString());
    }

    @Test
    void fallsBackToTheMatcherWhenTheTitleIsApproximate() {
        // The model can still be one word off after reading the listing; making it list the book again would
        // waste a round trip, so reading accepts a near title too.
        register(twoBooks(), "alpha", "beta");

        JsonObject result = call(arg("read", "mod", "alpha", "title", "mana"));

        assertEquals("OK", result.get("outcome").getAsString());
        assertEquals("Mana Pool", result.get("title").getAsString());
        assertEquals("search", result.get("via").getAsString());
    }

    @Test
    void reportsNoBooksRatherThanFailingWhenNothingShipsAGuide() {
        register(FakeModResources.builder().build(), "alpha");

        JsonObject result = call(arg("search", "query", "mana"));

        assertEquals("EMPTY", result.get("outcome").getAsString());
        assertEquals("NO_BOOKS", result.get("reason").getAsString());
    }

    @Test
    void reportsNoMatchWithAHint() {
        register(twoBooks(), "alpha", "beta");

        JsonObject result = call(arg("search", "query", "netherite"));

        assertEquals("EMPTY", result.get("outcome").getAsString());
        assertEquals("NO_MATCH", result.get("reason").getAsString());
        assertTrue(result.has("hint"), "a miss points the model at the listing");
    }

    @Test
    void rejectsAnEmptyQuery() {
        register(twoBooks(), "alpha", "beta");

        JsonObject result = call(arg("search", "query", "  "));

        assertEquals("FAILED", result.get("outcome").getAsString());
        assertEquals("QUERY_REQUIRED", result.get("reason").getAsString());
    }

    @Test
    void rejectsAnUnknownAction() {
        register(twoBooks(), "alpha", "beta");

        JsonObject result = call(arg("browse"));

        assertEquals("FAILED", result.get("outcome").getAsString());
        assertEquals("UNKNOWN_ACTION", result.get("reason").getAsString());
    }

    @Test
    void capsTheBodyItHandsBack() {
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < 400; index++) {
            body.append("mana ");
        }
        register(FakeModResources.builder()
                .put("alpha", "assets/alpha/patchouli_books/guide/book.json", "{}")
                .put("alpha", "assets/alpha/patchouli_books/guide/en_us/entries/mana.json",
                        "{\"name\":\"Mana Pool\",\"pages\":[{\"type\":\"text\",\"text\":\"" + body + "\"}]}")
                .build(), "alpha");

        String text = call(arg("search", "query", "mana")).getAsJsonArray("matches").get(0)
                .getAsJsonObject().get("text").getAsString();

        assertTrue(text.length() <= 1800, "one answer must not flood the agent's context: " + text.length());
        assertTrue(text.endsWith("…"), "a cut body is marked as cut");
    }

    private static FakeModResources twoBooks() {
        return FakeModResources.builder()
                .put("alpha", "assets/alpha/patchouli_books/guide/book.json", "{}")
                .put("alpha", "assets/alpha/patchouli_books/guide/en_us/entries/mana.json",
                        "{\"name\":\"Mana Pool\",\"pages\":[{\"type\":\"text\",\"text\":\"stores mana\"}]}")
                .put("beta", "assets/beta/patchouli_books/tome/book.json", "{}")
                .put("beta", "assets/beta/patchouli_books/tome/en_us/entries/flowers.json",
                        "{\"name\":\"Flowers\",\"pages\":[{\"type\":\"text\",\"text\":\"feed the mana pool\"}]}")
                .build();
    }

    private static void register(ModResources resources, String... modIds) {
        List<String> ids = List.of(modIds);
        TianshuRuntimePorts.register(InstalledModIds.class, () -> ids);
        TianshuRuntimePorts.register(ModResources.class, resources);
    }

    private static JsonObject arg(String action, String... pairs) {
        JsonObject args = new JsonObject();
        args.addProperty("action", action);
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            args.addProperty(pairs[index], pairs[index + 1]);
        }
        return args;
    }

    private static JsonObject call(JsonObject args) {
        DubheSkill skill = DubheBookSkill.skill(LANGUAGE);
        return skill.executor().execute(new DubheSkillContext(null, null, null), args).join();
    }
}