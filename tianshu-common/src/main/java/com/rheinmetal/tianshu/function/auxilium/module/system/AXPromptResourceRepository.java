package com.rheinmetal.tianshu.function.auxilium.module.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AXPromptResourceRepository {
    private static final String BUILTIN_TEXTS_RESOURCE = "/com/rheinmetal/tianshu/function/auxilium/prompts/ax_prompt_texts.json";
    private static final String[] BUILTIN_PROFILE_RESOURCES = {
            "/com/rheinmetal/tianshu/function/auxilium/prompts/general_ax.en_us.default.json",
            "/com/rheinmetal/tianshu/function/auxilium/prompts/general_ax.zh_cn.default.json"
    };

    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;

    public AXPromptResourceRepository(AXStorageLayout layout, AXJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
        ensureExternalTextsCatalog();
        ensureExternalProfileCatalog();
    }

    public AXPromptProfile loadProfile(AXPromptTask task, AXPromptLanguage language, String variant) {
        AXPromptTask effectiveTask = task == null ? AXPromptTask.GENERAL_AX : task;
        AXPromptLanguage effectiveLanguage = language == null ? AXPromptLanguage.EN_US : language;
        return loadProfile(new AXPromptResourceKey(effectiveTask, effectiveLanguage, variant))
                .or(() -> loadProfile(new AXPromptResourceKey(effectiveTask, AXPromptLanguage.EN_US, variant)))
                .or(() -> loadProfile(new AXPromptResourceKey(AXPromptTask.GENERAL_AX, effectiveLanguage, variant)))
                .or(() -> loadProfile(new AXPromptResourceKey(AXPromptTask.GENERAL_AX, AXPromptLanguage.EN_US, variant)))
                .orElseGet(() -> AXPromptProfile.defaultFor(effectiveTask, effectiveLanguage));
    }

    public AXPromptTexts loadTexts(AXPromptLanguage language) {
        AXPromptLanguage effectiveLanguage = language == null ? AXPromptLanguage.EN_US : language;
        Map<String, String> values = new LinkedHashMap<>();
        AXPromptTexts.mergeCatalog(values, readBuiltinTexts(), AXPromptLanguage.EN_US);
        if (effectiveLanguage != AXPromptLanguage.EN_US) {
            AXPromptTexts.mergeCatalog(values, readBuiltinTexts(), effectiveLanguage);
        }
        JsonObject external = readExternalTexts();
        AXPromptTexts.mergeCatalog(values, external, AXPromptLanguage.EN_US);
        if (effectiveLanguage != AXPromptLanguage.EN_US) {
            AXPromptTexts.mergeCatalog(values, external, effectiveLanguage);
        }
        return new AXPromptTexts(effectiveLanguage, values);
    }

    private Optional<AXPromptProfile> loadProfile(AXPromptResourceKey key) {
        if (layout == null || jsonStore == null || key == null) {
            return Optional.empty();
        }
        Path path = layout.sharedRoot().resolve("prompts").resolve(key.fileName());
        return jsonStore.readObject(path).map(json -> toProfile(key, json));
    }

    private AXPromptProfile toProfile(AXPromptResourceKey key, JsonObject json) {
        AXPromptProfile fallback = AXPromptProfile.defaultFor(key.task(), key.language());
        String identity = readString(json, "identity", fallback.identity());
        String behaviorRules = readString(json, "behaviorRules", fallback.behaviorRules());
        List<String> sectionOrder = readStringArray(json, "sectionOrder");
        if (sectionOrder.isEmpty()) {
            sectionOrder = fallback.sectionOrder();
        }
        return new AXPromptProfile(key.task(), key.language(), identity, behaviorRules, sectionOrder);
    }

    private String readString(JsonObject json, String key, String fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        String value = json.get(key).getAsString();
        return value == null || value.isBlank() ? fallback : value;
    }

    private List<String> readStringArray(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || !json.get(key).isJsonArray()) {
            return List.of();
        }
        JsonArray array = json.getAsJsonArray(key);
        List<String> result = new ArrayList<>();
        for (JsonElement element : array) {
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

    private JsonObject readExternalTexts() {
        if (layout == null || jsonStore == null) {
            return null;
        }
        return jsonStore.readObject(layout.promptTextsFile()).orElse(null);
    }

    private JsonObject readBuiltinTexts() {
        return readResource(BUILTIN_TEXTS_RESOURCE);
    }

    private JsonObject readResource(String resource) {
        try (InputStream stream = AXPromptResourceRepository.class.getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }
            try (java.io.Reader reader = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonElement element = com.google.gson.JsonParser.parseReader(reader);
                return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private void ensureExternalTextsCatalog() {
        if (layout == null || Files.isRegularFile(layout.promptTextsFile())) {
            return;
        }
        try (InputStream stream = AXPromptResourceRepository.class.getResourceAsStream(BUILTIN_TEXTS_RESOURCE)) {
            if (stream == null) {
                return;
            }
            Files.createDirectories(layout.promptsRoot());
            Files.copy(stream, layout.promptTextsFile());
        } catch (Exception ignored) {
        }
    }

    private void ensureExternalProfileCatalog() {
        if (layout == null) {
            return;
        }
        Path promptDir = layout.sharedRoot().resolve("prompts");
        try {
            Files.createDirectories(promptDir);
            for (String resource : BUILTIN_PROFILE_RESOURCES) {
                Path target = promptDir.resolve(resource.substring(resource.lastIndexOf('/') + 1));
                if (Files.isRegularFile(target)) {
                    continue;
                }
                try (InputStream stream = AXPromptResourceRepository.class.getResourceAsStream(resource)) {
                    if (stream == null) {
                        continue;
                    }
                    Files.copy(stream, target);
                }
            }
        } catch (Exception ignored) {
        }
    }

}
