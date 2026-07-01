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

    public static final String SYSTEM_TITLE_IDENTITY = "system.title.identity";
    public static final String SYSTEM_TITLE_BEHAVIOR_RULES = "system.title.behavior_rules";
    public static final String SYSTEM_TITLE_SECTION_RULES = "system.title.section_rules";
    public static final String SYSTEM_SECTION_RULES = "system.section_rules";
    public static final String SYSTEM_PARAGRAPH = "system.paragraph";
    public static final String SECTION_AX_SYSTEM = "section.ax_system";
    public static final String SECTION_GAME_CONTEXT = "section.game_context";
    public static final String GAME_CONTEXT_DYNAMIC_CONTENT_TITLE = "game_context.dynamic_content_title";
    public static final String GAME_CONTEXT_STATIC_CONTENT_TITLE = "game_context.static_content_title";
    public static final String GAME_CONTEXT_FACT_LINE = "game_context.fact_line";
    public static final String SECTION_PLAYER_MEMORY = "section.player_memory";
    public static final String PLAYER_MEMORY_RETRIEVED_TITLE = "player_memory.retrieved_title";
    public static final String PLAYER_MEMORY_RECENT_TITLE = "player_memory.recent_title";
    public static final String PLAYER_MEMORY_BLOCK_LINE = "player_memory.block_line";
    public static final String PLAYER_MEMORY_ATTACHED_TITLE = "player_memory.attached_messages.title";
    public static final String PLAYER_MEMORY_ATTACHED_HEADER = "player_memory.attached_messages.header";
    public static final String PLAYER_MEMORY_ATTACHED_LINE = "player_memory.attached_messages.line";
    public static final String SECTION_RECENT_DIALOGUE = "section.recent_dialogue";
    public static final String RECENT_DIALOGUE_LINE = "recent_dialogue.line";
    public static final String RECENT_DIALOGUE_USER_SPEAKER = "recent_dialogue.user_speaker";
    public static final String RECENT_DIALOGUE_ASSISTANT_SPEAKER = "recent_dialogue.assistant_speaker";
    public static final String RECENT_DIALOGUE_UNKNOWN_SPEAKER = "recent_dialogue.unknown_speaker";

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

    public static AXPromptTexts empty(AXPromptLanguage language) {
        return new AXPromptTexts(language, Map.of());
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
