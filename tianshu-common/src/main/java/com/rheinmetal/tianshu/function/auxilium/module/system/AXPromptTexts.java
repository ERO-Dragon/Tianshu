package com.rheinmetal.tianshu.function.auxilium.module.system;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record AXPromptTexts(
        AXPromptLanguage language,
        Map<String, String> values
) {
    private static final String BUILTIN_RESOURCE = "/com/rheinmetal/tianshu/function/auxilium/prompts/ax_prompt_texts.json";

    public static final String SECTION_AX_SYSTEM = "section.ax_system";
    public static final String SECTION_GAME_CONTEXT = "section.game_context";
    public static final String GAME_CONTEXT_CURRENT_SITUATION_INTRO = "game_context.current_situation_intro";
    public static final String GAME_CONTEXT_RELEVANT_KNOWLEDGE_INTRO = "game_context.relevant_knowledge_intro";
    public static final String GAME_CONTEXT_FACT_LINE = "game_context.fact_line";
    public static final String DYNAMIC_KNOWLEDGE_PLAYER_STATUS = "dynamic_knowledge.player.status";
    public static final String DYNAMIC_KNOWLEDGE_WORLD_ENVIRONMENT = "dynamic_knowledge.world.environment";
    public static final String DYNAMIC_KNOWLEDGE_INVENTORY_ITEMS = "dynamic_knowledge.inventory.items";
    public static final String DYNAMIC_KNOWLEDGE_INVENTORY_ITEM = "dynamic_knowledge.inventory.item";
    public static final String DYNAMIC_KNOWLEDGE_INVENTORY_SEPARATOR = "dynamic_knowledge.inventory.separator";
    public static final String DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_FEW = "dynamic_knowledge.inventory.amount.few";
    public static final String DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_LESS_THAN_HALF_STACK = "dynamic_knowledge.inventory.amount.less_than_half_stack";
    public static final String DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_HALF_STACK = "dynamic_knowledge.inventory.amount.half_stack";
    public static final String DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_MORE_THAN_HALF_STACK = "dynamic_knowledge.inventory.amount.more_than_half_stack";
    public static final String DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_ONE_STACK = "dynamic_knowledge.inventory.amount.one_stack";
    public static final String DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_ONE_STACK_MORE = "dynamic_knowledge.inventory.amount.one_stack_more";
    public static final String DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_MULTI_STACK = "dynamic_knowledge.inventory.amount.multi_stack";
    public static final String DYNAMIC_KNOWLEDGE_INVENTORY_AMOUNT_MULTI_STACK_MORE = "dynamic_knowledge.inventory.amount.multi_stack_more";
    public static final String DYNAMIC_KNOWLEDGE_INVENTORY_STACK_COUNT_PREFIX = "dynamic_knowledge.inventory.stack_count.";
    public static final String DYNAMIC_KNOWLEDGE_EFFECTS_ACTIVE = "dynamic_knowledge.effects.active";
    public static final String DYNAMIC_KNOWLEDGE_EFFECTS_ENTRY = "dynamic_knowledge.effects.entry";
    public static final String DYNAMIC_KNOWLEDGE_EFFECT_LEVEL_PREFIX = "dynamic_knowledge.effect.level.";
    public static final String DYNAMIC_KNOWLEDGE_DURATION_SECONDS = "dynamic_knowledge.duration.seconds";
    public static final String DYNAMIC_KNOWLEDGE_DURATION_MINUTES = "dynamic_knowledge.duration.minutes";
    public static final String DYNAMIC_KNOWLEDGE_DURATION_MINUTES_SECONDS = "dynamic_knowledge.duration.minutes_seconds";
    public static final String DYNAMIC_KNOWLEDGE_INTERACTION = "dynamic_knowledge.interaction";
    public static final String DYNAMIC_KNOWLEDGE_INTERACTION_HELD_ITEM = "dynamic_knowledge.interaction.held_item";
    public static final String DYNAMIC_KNOWLEDGE_INTERACTION_SCREEN = "dynamic_knowledge.interaction.screen";
    public static final String DYNAMIC_KNOWLEDGE_INTERACTION_USE_KEY = "dynamic_knowledge.interaction.use_key";
    public static final String DYNAMIC_KNOWLEDGE_INTERACTION_ATTACK_KEY = "dynamic_knowledge.interaction.attack_key";
    public static final String DYNAMIC_KNOWLEDGE_INTERACTION_SNEAKING = "dynamic_knowledge.interaction.sneaking";
    public static final String DYNAMIC_KNOWLEDGE_INTERACTION_CROSSHAIR = "dynamic_knowledge.interaction.crosshair";
    public static final String DYNAMIC_KNOWLEDGE_VALUE_UNKNOWN = "dynamic_knowledge.value.unknown";
    public static final String DYNAMIC_KNOWLEDGE_WEATHER_CLEAR = "dynamic_knowledge.weather.clear";
    public static final String DYNAMIC_KNOWLEDGE_WEATHER_RAIN = "dynamic_knowledge.weather.rain";
    public static final String DYNAMIC_KNOWLEDGE_WEATHER_THUNDER = "dynamic_knowledge.weather.thunder";
    public static final String SECTION_PLAYER_MEMORY = "section.player_memory";
    public static final String PLAYER_MEMORY_REMEMBERED_HISTORY_GROUP = "player_memory.remembered_history_group";
    public static final String PLAYER_MEMORY_RECENT_HISTORY_GROUP = "player_memory.recent_history_group";
    public static final String PLAYER_MEMORY_SUMMARY_LINE = "player_memory.summary_line";
    public static final String PLAYER_MEMORY_CONCURRENT_EVENTS_GROUP = "player_memory.concurrent_events_group";
    public static final String PLAYER_MEMORY_CONCURRENT_EVENT_LINE = "player_memory.concurrent_event_line";

    public AXPromptTexts {
        language = language == null ? AXPromptLanguage.EN_US : language;
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public String text(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String value = values.get(key);
        return value == null ? "" : value;
    }

    public String render(String key, Map<String, String> variables) {
        String result = text(key);
        for (Map.Entry<String, String> entry : Objects.requireNonNullElse(variables, Map.<String, String>of()).entrySet()) {
            String name = entry.getKey();
            if (name != null && !name.isBlank()) {
                result = result.replace("{{" + name.trim() + "}}", clean(entry.getValue()));
            }
        }
        return result;
    }

    public static AXPromptTexts builtin(AXPromptLanguage language) {
        AXPromptLanguage effectiveLanguage = language == null ? AXPromptLanguage.EN_US : language;
        return new AXPromptTexts(effectiveLanguage, valuesFromCatalog(readBuiltinCatalog(), effectiveLanguage));
    }

    public static Map<String, String> valuesFromCatalog(JsonObject catalog, AXPromptLanguage language) {
        AXPromptLanguage effectiveLanguage = language == null ? AXPromptLanguage.EN_US : language;
        Map<String, String> values = new LinkedHashMap<>();
        mergeCatalog(values, catalog, AXPromptLanguage.EN_US);
        if (effectiveLanguage != AXPromptLanguage.EN_US) {
            mergeCatalog(values, catalog, effectiveLanguage);
        }
        return Map.copyOf(values);
    }

    public static void mergeCatalog(Map<String, String> output, JsonObject catalog, AXPromptLanguage language) {
        if (output == null || catalog == null || language == null) {
            return;
        }
        JsonObject texts = catalog.has("texts") && catalog.get("texts").isJsonObject()
                ? catalog.getAsJsonObject("texts")
                : catalog;
        for (Map.Entry<String, JsonElement> entry : texts.entrySet()) {
            String key = entry.getKey();
            JsonElement element = entry.getValue();
            if (key == null || key.isBlank() || element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject localized = element.getAsJsonObject();
            if (!localized.has(language.code()) || localized.get(language.code()).isJsonNull()) {
                continue;
            }
            String value = localized.get(language.code()).getAsString();
            if (value != null && !value.isBlank()) {
                output.put(key.trim(), value);
            }
        }
    }

    private static JsonObject readBuiltinCatalog() {
        try (InputStream stream = AXPromptTexts.class.getResourceAsStream(BUILTIN_RESOURCE)) {
            if (stream == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonElement element = JsonParser.parseReader(reader);
                return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
