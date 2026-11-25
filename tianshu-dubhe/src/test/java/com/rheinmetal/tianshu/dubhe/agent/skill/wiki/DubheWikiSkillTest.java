package com.rheinmetal.tianshu.dubhe.agent.skill.wiki;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkill;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillContext;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheWikiSkillTest {

    private static final DubhePromptLanguageProvider CHINESE =
            DubhePromptLanguageProvider.fixed(DubhePromptLanguage.ZH_CN);
    private static final DubhePromptLanguageProvider ENGLISH =
            DubhePromptLanguageProvider.fixed(DubhePromptLanguage.EN_US);

    private static final String ANSWER = """
            {"query":{"pages":[
              {"pageid":6721,"title":"红石粉","index":1,
               "extract":"红石粉（Redstone Dust）是一种有多种用途的原材料物品。"},
              {"pageid":60972,"title":"Java版20w18a","index":2,"extract":"20w18a是Java版1.16的第14个快照。"}
            ]}}""";

    @Test
    void returnsTitlesProseAndTheArticleUrl() {
        AtomicReference<String> asked = new AtomicReference<>();
        JsonObject result = call(CHINESE, url -> {
            asked.set(url);
            return CompletableFuture.completedFuture(Optional.of(ANSWER));
        }, args("红石粉"));

        assertEquals("OK", result.get("outcome").getAsString());
        JsonObject first = result.getAsJsonArray("matches").get(0).getAsJsonObject();
        assertEquals("红石粉", first.get("title").getAsString());
        assertTrue(first.get("text").getAsString().contains("多种用途"));
        assertEquals("https://zh.minecraft.wiki/wiki/%E7%BA%A2%E7%9F%B3%E7%B2%89",
                first.get("url").getAsString());
        assertTrue(asked.get().contains("gsrsearch="), "the query travels in the search parameter");
        assertTrue(asked.get().contains("exintro=1"), "only the intro is requested, not the whole article");
    }

    @Test
    void picksTheWikiInThePlayersLanguage() {
        AtomicReference<String> asked = new AtomicReference<>();
        call(ENGLISH, url -> {
            asked.set(url);
            return CompletableFuture.completedFuture(Optional.of(ANSWER));
        }, args("redstone"));

        assertTrue(asked.get().startsWith("https://minecraft.wiki/api.php"), asked.get());
    }

    @Test
    void honoursAnExplicitSite() {
        AtomicReference<String> asked = new AtomicReference<>();
        JsonObject args = args("redstone");
        args.addProperty("site", "zh");
        call(ENGLISH, url -> {
            asked.set(url);
            return CompletableFuture.completedFuture(Optional.of(ANSWER));
        }, args);

        assertTrue(asked.get().startsWith("https://zh.minecraft.wiki/api.php"), asked.get());
    }

    @Test
    void reportsNoAnswerWhenTheFetchReturnsNothing() {
        JsonObject result = call(CHINESE, url -> CompletableFuture.completedFuture(Optional.empty()),
                args("红石粉"));

        assertEquals("EMPTY", result.get("outcome").getAsString());
        assertEquals("NO_ANSWER", result.get("reason").getAsString());
    }

    @Test
    void reportsBadResponseRatherThanThrowing() {
        JsonObject result = call(CHINESE, url -> CompletableFuture.completedFuture(Optional.of("<html>nope")),
                args("红石粉"));

        assertEquals("EMPTY", result.get("outcome").getAsString());
        assertEquals("BAD_RESPONSE", result.get("reason").getAsString());
    }

    @Test
    void skipsPagesWithoutAnExtract() {
        String body = "{\"query\":{\"pages\":[{\"title\":\"空页\"}]}}";
        JsonObject result = call(CHINESE, url -> CompletableFuture.completedFuture(Optional.of(body)),
                args("空页"));

        assertEquals("EMPTY", result.get("outcome").getAsString());
        assertEquals("NO_MATCH", result.get("reason").getAsString());
    }

    @Test
    void rejectsAnEmptyQuery() {
        JsonObject result = call(CHINESE,
                url -> CompletableFuture.completedFuture(Optional.of(ANSWER)), args("  "));

        assertEquals("FAILED", result.get("outcome").getAsString());
        assertEquals("QUERY_REQUIRED", result.get("reason").getAsString());
    }

    @Test
    void capsHowMuchProseOneAnswerCarries() {
        StringBuilder longExtract = new StringBuilder();
        for (int index = 0; index < 500; index++) {
            longExtract.append("红石信号。");
        }
        String body = "{\"query\":{\"pages\":[{\"title\":\"红石\",\"extract\":\"" + longExtract + "\"}]}}";
        JsonObject result = call(CHINESE, url -> CompletableFuture.completedFuture(Optional.of(body)),
                args("红石"));

        String text = result.getAsJsonArray("matches").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text.length() <= 900, "one page is capped before the answer total: " + text.length());
        assertTrue(text.endsWith("…"), "a cut extract is marked");
    }

    private static JsonObject args(String query) {
        JsonObject args = new JsonObject();
        args.addProperty("query", query);
        return args;
    }

    private static JsonObject call(DubhePromptLanguageProvider languages,
                                   com.rheinmetal.tianshu.dubhe.shared.web.DubheWeb.Fetcher fetcher,
                                   JsonObject args) {
        DubheSkill skill = DubheWikiSkill.skill(languages, fetcher);
        return skill.executor().execute(new DubheSkillContext(null, null, null), args).join();
    }
}