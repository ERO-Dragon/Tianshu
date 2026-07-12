package com.rheinmetal.tianshu.function.auxilium.module.system;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

public record AXPromptProfile(
        AXPromptTask task,
        AXPromptLanguage language,
        AXSystemPromptSet systemPrompts,
        List<String> sectionOrder
) {
    public AXPromptProfile {
        task = task == null ? AXPromptTask.GENERAL_AX : task;
        language = language == null ? AXPromptLanguage.EN_US : language;
        systemPrompts = systemPrompts == null ? AXSystemPromptSet.single("") : systemPrompts;
        sectionOrder = sectionOrder == null ? List.of() : List.copyOf(sectionOrder);
    }

    public static AXPromptProfile defaultFor(AXPromptTask task, AXPromptLanguage language) {
        AXPromptLanguage effectiveLanguage = language == null ? AXPromptLanguage.EN_US : language;
        AXPromptTask effectiveTask = task == null ? AXPromptTask.GENERAL_AX : task;
        return loadBuiltin(effectiveTask, effectiveLanguage).orElseGet(() -> new AXPromptProfile(
                effectiveTask,
                effectiveLanguage,
                AXSystemPromptSet.single(""),
                DEFAULT_SECTION_ORDER
        ));
    }

    private static Optional<AXPromptProfile> loadBuiltin(AXPromptTask task, AXPromptLanguage language) {
        String resource = "/com/rheinmetal/tianshu/function/auxilium/prompts/" + new AXPromptResourceKey(task, language, "default").fileName();
        try (InputStream stream = AXPromptProfile.class.getResourceAsStream(resource)) {
            if (stream == null) {
                return Optional.empty();
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonElement element = JsonParser.parseReader(reader);
                if (element == null || !element.isJsonObject()) {
                    return Optional.empty();
                }
                return Optional.of(fromJson(task, language, element.getAsJsonObject()));
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static AXPromptProfile fromJson(AXPromptTask task, AXPromptLanguage language, JsonObject json) {
        AXSystemPromptSet systemPrompts = readSystemPrompts(json, AXSystemPromptSet.single(""));
        List<String> sectionOrder = readStringArray(json, "sectionOrder");
        if (sectionOrder.isEmpty()) {
            sectionOrder = DEFAULT_SECTION_ORDER;
        }
        return new AXPromptProfile(task, language, systemPrompts, sectionOrder);
    }

    static AXSystemPromptSet readSystemPrompts(JsonObject json, AXSystemPromptSet fallback) {
        AXSystemPromptSet effectiveFallback = fallback == null ? AXSystemPromptSet.single("") : fallback;
        if (json == null || !json.has("systemPrompts") || !json.get("systemPrompts").isJsonObject()) {
            return effectiveFallback;
        }
        JsonObject prompts = json.getAsJsonObject("systemPrompts");
        return new AXSystemPromptSet(
                readString(prompts, "short", effectiveFallback.shortPrompt()),
                readString(prompts, "standard", effectiveFallback.standardPrompt()),
                readString(prompts, "full", effectiveFallback.fullPrompt())
        );
    }

    static String readString(JsonObject json, String key, String fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        String value = json.get(key).getAsString();
        return value == null || value.isBlank() ? fallback : value;
    }

    private static List<String> readStringArray(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || !json.get(key).isJsonArray()) {
            return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        for (JsonElement element : json.getAsJsonArray(key)) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            String value = element.getAsString();
            if (value != null && !value.isBlank()) {
                result.add(value.trim());
            }
        }
        return List.copyOf(result);
    }

    private static final List<String> DEFAULT_SECTION_ORDER = List.of(
            "ax_system",
            "game_context",
            "player_memory",
            "recent_dialogue",
            "current_input"
    );
}
