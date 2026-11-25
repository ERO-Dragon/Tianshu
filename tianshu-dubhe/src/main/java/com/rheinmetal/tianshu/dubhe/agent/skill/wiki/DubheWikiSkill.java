package com.rheinmetal.tianshu.dubhe.agent.skill.wiki;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkill;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillBudget;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillLane;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillSpec;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillVisibility;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;
import com.rheinmetal.tianshu.dubhe.shared.web.DubheWeb;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 查 Minecraft Wiki：原版内容的来源。
 *
 * <p>一次请求拿全：搜索、取导语、带标题。MediaWiki 的 {@code generator=search} 让"找到哪几页"和"那几页讲
 * 什么"合并成一次往返——两段式（先搜后取）在游戏对话里是双倍延迟，而导语已经够回答"这是什么"。</p>
 *
 * <p>只用 {@code exintro}：整篇正文动辄几万字，而玩家问的是"红石粉是干嘛的"，导语那一两句正是答案。
 * 真要细节，模型自己会说"我去查一下"然后再问一次。</p>
 */
public final class DubheWikiSkill {

    public static final String ID = "wiki";

    private static final int DEFAULT_LIMIT = 2;
    private static final int MAX_LIMIT = 4;

    /** 单页导语上限；导语正常只有一两句，这个值只是防某页把整段塞进来。 */
    private static final int MAX_PAGE_CHARS = 900;

    /** 一次回答的正文上限。 */
    private static final int MAX_TEXT_CHARS = 1800;

    private DubheWikiSkill() {
    }

    public static DubheSkill skill(DubhePromptLanguageProvider languages) {
        return skill(languages, DubheWeb::get);
    }

    static DubheSkill skill(DubhePromptLanguageProvider languages, DubheWeb.Fetcher fetcher) {
        DubheSkillSpec spec = DubheSkillSpec.builder(ID, "查 Minecraft Wiki（原版机制、物品、方块），返回导语摘要")
                .inputSchema(schema())
                .visibility(DubheSkillVisibility.INTERNAL)
                .budget(DubheSkillBudget.of(20_000L, 1))
                .reportSchema(List.of("outcome"))
                .retry(false)
                .lane(DubheSkillLane.PLAYER)
                .build();
        return new DubheSkill(spec, (context, args) -> search(languages, fetcher, args));
    }

    private static JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        properties.add("query", property("string", "要查的词，用玩家自己的说法，例如「红石粉怎么用」"));
        JsonObject site = property("string", "哪个语言的 Wiki；不给就跟玩家语言走");
        JsonArray sites = new JsonArray();
        sites.add("zh");
        sites.add("en");
        site.add("enum", sites);
        properties.add("site", site);
        properties.add("limit", property("integer", "最多回几条，默认 2"));
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("query");
        schema.add("required", required);
        return schema;
    }

    private static CompletableFuture<JsonObject> search(DubhePromptLanguageProvider languages,
                                                        DubheWeb.Fetcher fetcher, JsonObject args) {
        String query = text(args, "query").trim();
        if (query.isEmpty()) {
            return CompletableFuture.completedFuture(failed("QUERY_REQUIRED"));
        }
        Site site = Site.of(text(args, "site"), languages);
        int limit = Math.max(1, Math.min(MAX_LIMIT, integer(args, "limit", DEFAULT_LIMIT)));
        return fetcher.get(site.searchUrl(query, limit)).thenApply(body -> parse(site, body));
    }

    private static JsonObject parse(Site site, Optional<String> body) {
        if (body.isEmpty() || body.get().isBlank()) {
            return empty("NO_ANSWER");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(body.get()).getAsJsonObject();
        } catch (RuntimeException failure) {
            LogSink.SYSTEM.warn("wiki.response.invalid site=" + site.code, failure);
            return empty("BAD_RESPONSE");
        }
        JsonObject query = root.has("query") && root.get("query").isJsonObject()
                ? root.getAsJsonObject("query") : null;
        JsonElement pages = query == null ? null : query.get("pages");
        if (pages == null || !pages.isJsonArray()) {
            return empty("NO_MATCH");
        }
        JsonArray matches = new JsonArray();
        int remaining = MAX_TEXT_CHARS;
        for (JsonElement element : pages.getAsJsonArray()) {
            if (remaining <= 0) {
                break;
            }
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject page = element.getAsJsonObject();
            String extract = text(page, "extract").trim();
            if (extract.isEmpty()) {
                continue;
            }
            String title = text(page, "title").trim();
            String text = truncate(extract, Math.min(MAX_PAGE_CHARS, remaining));
            remaining -= text.length();
            JsonObject row = new JsonObject();
            row.addProperty("title", title);
            row.addProperty("text", text);
            row.addProperty("url", site.articleUrl(title));
            matches.add(row);
        }
        if (matches.isEmpty()) {
            return empty("NO_MATCH");
        }
        JsonObject result = new JsonObject();
        result.addProperty("outcome", "OK");
        result.addProperty("site", site.code);
        result.add("matches", matches);
        return result;
    }

    private static String truncate(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, Math.max(0, limit - 1)) + "…";
    }

    private static JsonObject failed(String reason) {
        JsonObject result = new JsonObject();
        result.addProperty("outcome", "FAILED");
        result.addProperty("reason", reason);
        return result;
    }

    private static JsonObject empty(String reason) {
        JsonObject result = new JsonObject();
        result.addProperty("outcome", "EMPTY");
        result.addProperty("reason", reason);
        return result;
    }

    private static int integer(JsonObject args, String name, int fallback) {
        JsonElement value = args == null ? null : args.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException failure) {
            return fallback;
        }
    }

    private static String text(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    private static JsonObject property(String type, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", type);
        property.addProperty("description", description);
        return property;
    }

    /**
     * The two wikis that answer. Both run MediaWiki with the TextExtracts extension, so one query shape serves
     * both and only the host changes.
     *
     * <p>BWIKI is deliberately absent: it has no extracts API, and its raw wikitext is tens of kilobytes of
     * infobox markup that would cost more to clean than the answer is worth.</p>
     */
    enum Site {
        ZH("zh", "https://zh.minecraft.wiki/api.php", "https://zh.minecraft.wiki/wiki/"),
        EN("en", "https://minecraft.wiki/api.php", "https://minecraft.wiki/wiki/");

        final String code;
        private final String api;
        private final String articles;

        Site(String code, String api, String articles) {
            this.code = code;
            this.api = api;
            this.articles = articles;
        }

        static Site of(String requested, DubhePromptLanguageProvider languages) {
            String code = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
            if (!code.isEmpty()) {
                for (Site site : values()) {
                    if (site.code.equals(code)) {
                        return site;
                    }
                }
            }
            return languages != null && languages.currentLanguage() == DubhePromptLanguage.ZH_CN ? ZH : EN;
        }

        String searchUrl(String query, int limit) {
            return api + "?action=query"
                    + "&generator=search&gsrsearch=" + encode(query) + "&gsrlimit=" + limit
                    + "&prop=extracts&explaintext=1&exintro=1&exlimit=" + limit
                    + "&redirects=1&format=json&formatversion=2";
        }

        String articleUrl(String title) {
            return articles + encode(title.replace(' ', '_')).replace("+", "_");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}