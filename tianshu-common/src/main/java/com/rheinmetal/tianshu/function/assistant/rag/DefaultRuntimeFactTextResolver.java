package com.rheinmetal.tianshu.function.assistant.rag;

import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptLanguage;

import java.util.Locale;
import java.util.Map;

public final class DefaultRuntimeFactTextResolver implements RuntimeFactTextResolver {
    private static final String DEFAULT_LANGUAGE_CODE = "en_us";
    private static final DefaultRuntimeFactTextResolver INSTANCE = new DefaultRuntimeFactTextResolver();
    private static final Map<String, Map<String, String>> TEXTS = Map.of(
            "en_us", Map.ofEntries(
                    Map.entry("tianshu.llm.rag.value.unknown", "unknown"),
                    Map.entry("tianshu.llm.rag.player.dimension", "The player is currently in {dimension}."),
                    Map.entry("tianshu.llm.rag.player.status", "The player's health is {health}, hunger is {hunger}, saturation is {saturation}, and experience level is {experienceLevel}."),
                    Map.entry("tianshu.llm.rag.world.environment", "The player is currently in the {biome} biome. The weather is {weather}, and the current in-game time is {time}."),
                    Map.entry("tianshu.llm.rag.inventory.empty", "The player's inventory has no recorded items."),
                    Map.entry("tianshu.llm.rag.inventory.items", "The player's inventory contains: {items}."),
                    Map.entry("tianshu.llm.rag.inventory.item", "{count} {name}"),
                    Map.entry("tianshu.llm.rag.inventory.separator", ", "),
                    Map.entry("tianshu.llm.rag.inventory.more", "and {count} more item types"),
                    Map.entry("tianshu.llm.rag.weather.clear", "clear"),
                    Map.entry("tianshu.llm.rag.weather.rain", "rain"),
                    Map.entry("tianshu.llm.rag.weather.thunderstorm", "thunderstorm"),
                    Map.entry("tianshu.llm.rag.time.expression", "{period} {hour} o'clock"),
                    Map.entry("tianshu.llm.rag.time.late_night", "late night"),
                    Map.entry("tianshu.llm.rag.time.early_morning", "early morning"),
                    Map.entry("tianshu.llm.rag.time.morning", "morning"),
                    Map.entry("tianshu.llm.rag.time.noon", "noon"),
                    Map.entry("tianshu.llm.rag.time.afternoon", "afternoon"),
                    Map.entry("tianshu.llm.rag.time.evening", "evening"),
                    Map.entry("tianshu.llm.rag.time.night", "night"),
                    Map.entry("tianshu.llm.rag.dimension.unknown", "an unknown dimension"),
                    Map.entry("tianshu.llm.rag.biome.unknown", "unknown biome")
            ),
            "zh_cn", Map.ofEntries(
                    Map.entry("tianshu.llm.rag.value.unknown", "未知"),
                    Map.entry("tianshu.llm.rag.player.dimension", "当前玩家所在维度是{dimension}。"),
                    Map.entry("tianshu.llm.rag.player.status", "当前玩家生命值为{health}，饥饿值为{hunger}，饱和度为{saturation}，经验等级为{experienceLevel}。"),
                    Map.entry("tianshu.llm.rag.world.environment", "当前玩家所在生物群系是{biome}，天气是{weather}，当前时间为{time}。"),
                    Map.entry("tianshu.llm.rag.inventory.empty", "当前玩家背包中没有可记录的物品。"),
                    Map.entry("tianshu.llm.rag.inventory.items", "当前玩家背包中有：{items}。"),
                    Map.entry("tianshu.llm.rag.inventory.item", "{name}{count}个"),
                    Map.entry("tianshu.llm.rag.inventory.separator", "，"),
                    Map.entry("tianshu.llm.rag.inventory.more", "以及另外{count}种物品"),
                    Map.entry("tianshu.llm.rag.weather.clear", "晴朗"),
                    Map.entry("tianshu.llm.rag.weather.rain", "下雨"),
                    Map.entry("tianshu.llm.rag.weather.thunderstorm", "雷暴"),
                    Map.entry("tianshu.llm.rag.time.expression", "{period}{hour}点"),
                    Map.entry("tianshu.llm.rag.time.late_night", "深夜"),
                    Map.entry("tianshu.llm.rag.time.early_morning", "清晨"),
                    Map.entry("tianshu.llm.rag.time.morning", "上午"),
                    Map.entry("tianshu.llm.rag.time.noon", "中午"),
                    Map.entry("tianshu.llm.rag.time.afternoon", "下午"),
                    Map.entry("tianshu.llm.rag.time.evening", "傍晚"),
                    Map.entry("tianshu.llm.rag.time.night", "夜晚"),
                    Map.entry("tianshu.llm.rag.dimension.unknown", "未知维度"),
                    Map.entry("tianshu.llm.rag.biome.unknown", "未知生物群系")
            )
    );

    public static DefaultRuntimeFactTextResolver instance() {
        return INSTANCE;
    }

    @Override
    public String text(AssistantPromptLanguage language, String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String normalizedLanguage = normalize(language == null ? null : language.code());
        String value = TEXTS.getOrDefault(normalizedLanguage, Map.of()).get(key);
        if (value != null) {
            return value;
        }
        return TEXTS.getOrDefault(DEFAULT_LANGUAGE_CODE, Map.of()).getOrDefault(key, key);
    }

    private String normalize(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return DEFAULT_LANGUAGE_CODE;
        }
        return languageCode.trim().toLowerCase(Locale.ROOT);
    }
}
