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

/**
 * One prompt profile: the single system prompt, the section order, and the task/language keys.
 *
 * <p>{@code systemPrompt} is the complete system prompt. There are no length variants any more: the
 * same bytes are emitted on every turn so the provider can cache the whole prefix, and the context
 * tier only scales the surrounding sections, never this text (see {@code AXSystemPromptContributor}).</p>
 */
public record AXPromptProfile(
        AXPromptTask task,
        AXPromptLanguage language,
        String systemPrompt,
        List<String> sectionOrder
) {
    /** Profile key holding the prompt object. */
    private static final String KEY_SYSTEM_PROMPTS = "systemPrompts";

    /** Prompt-object key of the single system prompt. */
    private static final String KEY_SYSTEM = "system";

    /**
     * Keys of the removed short/standard/full model, in the order a reader must prefer them.
     *
     * <p>A profile file written by an older build still carries all three; the most complete variant
     * wins so behaviour matches what the assembler used before the model collapsed into one prompt.</p>
     */
    private static final List<String> LEGACY_PROMPT_KEYS = List.of("full", "standard", "short");

    public AXPromptProfile {
        task = task == null ? AXPromptTask.GENERAL_AX : task;
        language = language == null ? AXPromptLanguage.EN_US : language;
        systemPrompt = systemPrompt == null ? "" : systemPrompt.trim();
        sectionOrder = sectionOrder == null ? List.of() : List.copyOf(sectionOrder);
    }

    public static AXPromptProfile defaultFor(AXPromptTask task, AXPromptLanguage language) {
        AXPromptLanguage effectiveLanguage = language == null ? AXPromptLanguage.EN_US : language;
        AXPromptTask effectiveTask = task == null ? AXPromptTask.GENERAL_AX : task;
        return loadBuiltin(effectiveTask, effectiveLanguage).orElseGet(() -> new AXPromptProfile(
                effectiveTask,
                effectiveLanguage,
                "",
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
        String systemPrompt = readSystemPrompt(json, "");
        List<String> sectionOrder = readStringArray(json, "sectionOrder");
        if (sectionOrder.isEmpty()) {
            sectionOrder = DEFAULT_SECTION_ORDER;
        }
        return new AXPromptProfile(task, language, systemPrompt, sectionOrder);
    }

    /**
     * Reads the single system prompt out of a profile JSON object.
     *
     * <p>Current shape: {@code systemPrompts.system}. Profiles written before the three-tier model was
     * removed are still accepted; they fall back to their most complete variant
     * ({@code full} → {@code standard} → {@code short}), which is the text the assembler already used.
     * Only the current shape is ever written: {@link #KEY_SYSTEM} is the sole key emitted by this build.</p>
     */
    static String readSystemPrompt(JsonObject json, String fallback) {
        String effectiveFallback = fallback == null ? "" : fallback;
        if (json == null || !json.has(KEY_SYSTEM_PROMPTS) || !json.get(KEY_SYSTEM_PROMPTS).isJsonObject()) {
            return effectiveFallback;
        }
        JsonObject prompts = json.getAsJsonObject(KEY_SYSTEM_PROMPTS);
        String current = readString(prompts, KEY_SYSTEM, "");
        if (!current.isBlank()) {
            return current;
        }
        for (String legacyKey : LEGACY_PROMPT_KEYS) {
            String legacy = readString(prompts, legacyKey, "");
            if (!legacy.isBlank()) {
                return legacy;
            }
        }
        return effectiveFallback;
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
            "memory_summary",
            "game_context",
            "player_memory",
            "recent_dialogue",
            "current_input"
    );
}
