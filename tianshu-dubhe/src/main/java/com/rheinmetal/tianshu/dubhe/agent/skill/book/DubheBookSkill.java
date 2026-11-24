package com.rheinmetal.tianshu.dubhe.agent.skill.book;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.assembly.InstalledModIds;
import com.rheinmetal.tianshu.api.assembly.ModResources;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkill;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillBudget;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillLane;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillSpec;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillVisibility;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 查阅玩家已装模组自带的手册。
 *
 * <p>这是天枢回答"这个东西怎么用"的第一站，也是唯一不需要联网的一站：手册就在玩家正在运行的那个 jar 里，
 * 内容和游戏版本天然一致，不会过期、不会掉线。代价是书很大，所以技能只回命中的那几条，不整本交出去。</p>
 *
 * <p>三个动作对应模型能打的三条路：<b>search</b>（按说法找）、<b>list</b>（看目录）、<b>read</b>（读指定条目）。
 * 只有 search 时，模型遇到"它认得这个词、但词对不上"的情况就无路可走；list + read 是补这一段的，
 * 代价只在需要时付一次。</p>
 */
public final class DubheBookSkill {

    public static final String ID = "book";

    private static final int DEFAULT_LIMIT = 3;
    private static final int MAX_LIMIT = 5;

    /** 一页目录的条数：够模型扫出目标，又不至于一次塞进几千个标题。 */
    private static final int PAGE_SIZE = 300;

    /** 一次回答的正文上限：够读完两三条手册条目，又不至于把一次工具结果撑爆。 */
    private static final int MAX_TEXT_CHARS = 1800;

    private DubheBookSkill() {
    }

    public static DubheSkill skill(DubhePromptLanguageProvider languages) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        properties.add("action", property("string", "search 按说法找；list 看目录；read 读指定条目", "search", "list", "read"));
        properties.add("query", property("string", "search 用的说法，用玩家自己的话，例如「魔力池怎么用」"));
        properties.add("mod", property("string", "模组 id；list 不给就列出哪些模组有手册，search 给了就只在该模组内找"));
        properties.add("title", property("string", "read 用的条目标题，从 list 里抄"));
        properties.add("page", property("integer", "list 的页码，从 1 开始"));
        properties.add("limit", property("integer", "search 最多回几条，默认 3"));
        schema.add("properties", properties);
        schema.add("required", stringArray("action"));

        DubheSkillSpec spec = DubheSkillSpec.builder(ID, "查阅已装模组自带的手册（游戏内那本书）：按说法找、看目录、读指定条目")
                .inputSchema(schema)
                .visibility(DubheSkillVisibility.INTERNAL)
                .budget(DubheSkillBudget.of(20_000L, 1))
                .reportSchema(List.of("outcome"))
                .retry(false)
                .lane(DubheSkillLane.PLAYER)
                .build();
        Cache cache = new Cache(languages);
        return new DubheSkill(spec, (context, args) -> CompletableFuture.completedFuture(run(cache, args)));
    }

    private static JsonObject run(Cache cache, JsonObject args) {
        String action = text(args, "action").trim().toLowerCase(java.util.Locale.ROOT);
        DubheBookCatalog books;
        try {
            books = cache.get();
        } catch (RuntimeException failure) {
            LogSink.SYSTEM.warn("book.catalog.load_failed", failure);
            return failed("LOAD_FAILED");
        }
        if (books.isEmpty()) {
            return empty("NO_BOOKS");
        }
        return switch (action.isEmpty() ? "search" : action) {
            case "search" -> search(books, args);
            case "list" -> list(books, args);
            case "read" -> read(books, args);
            default -> failed("UNKNOWN_ACTION");
        };
    }

    private static JsonObject search(DubheBookCatalog books, JsonObject args) {
        String query = text(args, "query").trim();
        if (query.isEmpty()) {
            return failed("QUERY_REQUIRED");
        }
        String mod = text(args, "mod").trim();
        List<DubheBookEntry> matches = books.search(query, limit(args), mod.isEmpty() ? null : mod);
        if (matches.isEmpty()) {
            JsonObject result = empty("NO_MATCH");
            result.addProperty("hint", "换个说法，或先用 list 看目录");
            return result;
        }
        JsonObject result = new JsonObject();
        result.addProperty("outcome", "OK");
        result.add("matches", render(matches));
        return result;
    }

    private static JsonObject list(DubheBookCatalog books, JsonObject args) {
        String mod = text(args, "mod").trim();
        if (mod.isEmpty()) {
            JsonArray mods = new JsonArray();
            for (String modId : books.mods()) {
                JsonObject row = new JsonObject();
                row.addProperty("mod", modId);
                row.addProperty("entries", books.entryCount(modId));
                mods.add(row);
            }
            JsonObject result = new JsonObject();
            result.addProperty("outcome", "OK");
            result.add("mods", mods);
            return result;
        }
        if (books.entryCount(mod) == 0) {
            return empty("UNKNOWN_MOD");
        }
        int page = Math.max(1, integer(args, "page", 1));
        List<String> titles = books.titles(mod, page, PAGE_SIZE);
        if (titles.isEmpty()) {
            return empty("PAGE_OUT_OF_RANGE");
        }
        int pages = (books.entryCount(mod) + PAGE_SIZE - 1) / PAGE_SIZE;
        JsonObject result = new JsonObject();
        result.addProperty("outcome", "OK");
        result.addProperty("mod", mod);
        result.addProperty("page", page);
        result.addProperty("pages", pages);
        JsonArray array = new JsonArray();
        titles.forEach(array::add);
        result.add("titles", array);
        return result;
    }

    private static JsonObject read(DubheBookCatalog books, JsonObject args) {
        String mod = text(args, "mod").trim();
        String title = text(args, "title").trim();
        if (mod.isEmpty() || title.isEmpty()) {
            return failed("MOD_AND_TITLE_REQUIRED");
        }
        Optional<DubheBookEntry> exact = books.find(mod, title);
        if (exact.isPresent()) {
            return entry(exact.get(), "title");
        }
        // The model copied a title that does not match byte for byte: fall back to the matcher inside that mod
        // rather than making it list the whole book again to find the exact spelling.
        List<DubheBookEntry> near = books.search(title, 1, mod);
        if (near.isEmpty()) {
            return empty("TITLE_NOT_FOUND");
        }
        return entry(near.get(0), "search");
    }

    private static JsonObject entry(DubheBookEntry entry, String via) {
        JsonObject result = new JsonObject();
        result.addProperty("outcome", "OK");
        result.addProperty("mod", entry.modId());
        result.addProperty("title", entry.title());
        result.addProperty("text", truncate(entry.text(), MAX_TEXT_CHARS));
        result.addProperty("via", via);
        return result;
    }

    private static JsonArray render(List<DubheBookEntry> matches) {
        JsonArray array = new JsonArray();
        int remaining = MAX_TEXT_CHARS;
        for (DubheBookEntry entry : matches) {
            if (remaining <= 0) {
                break;
            }
            JsonObject row = new JsonObject();
            row.addProperty("mod", entry.modId());
            row.addProperty("title", entry.title());
            String text = truncate(entry.text(), remaining);
            remaining -= text.length();
            row.addProperty("text", text);
            array.add(row);
        }
        return array;
    }

    private static String truncate(String text, int remaining) {
        if (text.length() <= remaining) {
            return text;
        }
        int end = Math.max(0, remaining - 1);
        return text.substring(0, end) + "…";
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

    private static int limit(JsonObject args) {
        return Math.max(1, Math.min(MAX_LIMIT, integer(args, "limit", DEFAULT_LIMIT)));
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

    private static JsonObject property(String type, String description, String... values) {
        JsonObject property = new JsonObject();
        property.addProperty("type", type);
        property.addProperty("description", description);
        if (values.length > 0) {
            JsonArray array = new JsonArray();
            for (String value : values) {
                array.add(value);
            }
            property.add("enum", array);
        }
        return property;
    }

    private static JsonArray stringArray(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /**
     * 第一个查询才去读 jar，之后一直复用。
     *
     * <p>缓存挂在技能实例上而不是静态字段上：模块停掉时缓存跟着消失，测试也不会互相污染。</p>
     */
    private static final class Cache {

        private final DubhePromptLanguageProvider languages;
        private volatile DubheBookCatalog catalog;

        Cache(DubhePromptLanguageProvider languages) {
            this.languages = languages;
        }

        DubheBookCatalog get() {
            DubheBookCatalog current = catalog;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                if (catalog == null) {
                    catalog = DubheBookCatalog.load(
                            TianshuRuntimePorts.find(InstalledModIds.class).orElseGet(InstalledModIds::none),
                            TianshuRuntimePorts.find(ModResources.class).orElseGet(ModResources::none),
                            preferredLanguages(),
                            LogSink.SYSTEM);
                }
                return catalog;
            }
        }

        /** The player's own language first, then the rest: a book in their language reads better than one they cannot. */
        private List<String> preferredLanguages() {
            DubhePromptLanguage current = languages == null
                    ? DubhePromptLanguage.BASE : languages.currentLanguage();
            List<String> codes = new ArrayList<>();
            codes.add(current.code());
            for (DubhePromptLanguage language : DubhePromptLanguage.values()) {
                if (language != current) {
                    codes.add(language.code());
                }
            }
            return List.copyOf(codes);
        }
    }
}