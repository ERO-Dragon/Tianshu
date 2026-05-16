package com.rheinmetal.tianshu.function.assistant.rag;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptLanguage;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RuntimeFactLanguageBundle {
    private static final String RESOURCE_ROOT = "/assets/";
    private static final String DEFAULT_LANGUAGE_CODE = "en_us";
    private static final List<String> RESOURCE_NAMESPACES = List.of("tianshu", "minecraft");
    private static final RuntimeFactLanguageBundle DEFAULT = new RuntimeFactLanguageBundle();

    private final Map<String, Map<String, String>> bundles = new HashMap<>();

    public static RuntimeFactLanguageBundle defaultBundle() {
        return DEFAULT;
    }

    public String text(AssistantPromptLanguage language, String key) {
        String value = lookup(languageCode(language), key);
        if (value != null) {
            return value;
        }
        value = lookup(DEFAULT_LANGUAGE_CODE, key);
        return value == null ? key : value;
    }

    public String format(AssistantPromptLanguage language, String key, Map<String, String> arguments) {
        String template = text(language, key);
        if (arguments == null || arguments.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    private String lookup(String languageCode, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalizedLanguageCode = normalizeLanguageCode(languageCode);
        for (String namespace : RESOURCE_NAMESPACES) {
            String value = load(namespace, normalizedLanguageCode).get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Map<String, String> load(String namespace, String languageCode) {
        return bundles.computeIfAbsent(namespace + ":" + normalizeLanguageCode(languageCode), ignored -> loadResource(namespace, languageCode));
    }

    private Map<String, String> loadResource(String namespace, String languageCode) {
        String path = RESOURCE_ROOT + namespace + "/lang/" + normalizeLanguageCode(languageCode) + ".json";
        try (InputStream stream = RuntimeFactLanguageBundle.class.getResourceAsStream(path)) {
            if (stream == null) {
                return Map.of();
            }
            JsonElement element = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (element == null || !element.isJsonObject()) {
                return Map.of();
            }
            JsonObject object = element.getAsJsonObject();
            Map<String, String> result = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonPrimitive()) {
                    result.put(entry.getKey(), value.getAsString());
                }
            }
            return Map.copyOf(result);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String languageCode(AssistantPromptLanguage language) {
        AssistantPromptLanguage effectiveLanguage = language == null ? AssistantPromptLanguage.EN_US : language;
        return normalizeLanguageCode(effectiveLanguage.code());
    }

    private String normalizeLanguageCode(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return DEFAULT_LANGUAGE_CODE;
        }
        return languageCode.trim().toLowerCase(Locale.ROOT);
    }
}
