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

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 查阅玩家已装模组自带的手册。
 *
 * <p>这是天枢回答"这个东西怎么用"的第一站，也是唯一不需要联网的一站：手册就在玩家正在运行的那个 jar 里，
 * 内容和游戏版本天然一致，不会过期、不会掉线。代价是书很大，所以技能只回命中的那几条，不整本交出去。</p>
 *
 * <p>技能对模型只暴露"问什么、拿回什么"：它看不到 jar、看不到翻译键、看不到条目文件。解析、语言回退、
 * 索引全在 {@link DubheBookCatalog} 里。</p>
 */
public final class DubheBookSkill {

    public static final String ID = "book";

    private static final int DEFAULT_LIMIT = 3;
    private static final int MAX_LIMIT = 5;

    /** 一次回答的正文上限：够读完两三条手册条目，又不至于把一次工具结果撑爆。 */
    private static final int MAX_TEXT_CHARS = 1800;

    private DubheBookSkill() {
    }

    public static DubheSkill skill() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        properties.add("query", property("string", "要查的东西，用玩家自己的说法，例如「魔力池怎么用」"));
        properties.add("limit", property("integer", "最多回几条，默认 3"));
        schema.add("properties", properties);
        schema.add("required", stringArray("query"));

        DubheSkillSpec spec = DubheSkillSpec.builder(ID, "查阅已装模组自带的手册（游戏内那本书），回答物品、方块与机制的用法")
                .inputSchema(schema)
                .visibility(DubheSkillVisibility.INTERNAL)
                .budget(DubheSkillBudget.of(20_000L, 1))
                .reportSchema(List.of("outcome"))
                .retry(false)
                .lane(DubheSkillLane.PLAYER)
                .build();
        Cache cache = new Cache();
        return new DubheSkill(spec, (context, args) -> CompletableFuture.completedFuture(search(cache, args)));
    }

    private static JsonObject search(Cache cache, JsonObject args) {
        String query = text(args, "query").trim();
        if (query.isEmpty()) {
            return failed("QUERY_REQUIRED");
        }
        int limit = limit(args);
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
        List<DubheBookEntry> matches = books.search(query, limit);
        if (matches.isEmpty()) {
            return empty("NO_MATCH");
        }
        JsonObject result = new JsonObject();
        result.addProperty("outcome", "OK");
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
        result.add("matches", array);
        return result;
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
        JsonElement value = args == null ? null : args.get("limit");
        if (value == null || !value.isJsonPrimitive()) {
            return DEFAULT_LIMIT;
        }
        try {
            return Math.max(1, Math.min(MAX_LIMIT, value.getAsInt()));
        } catch (RuntimeException failure) {
            return DEFAULT_LIMIT;
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

        private volatile DubheBookCatalog catalog;

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
                            LogSink.SYSTEM);
                }
                return catalog;
            }
        }
    }
}