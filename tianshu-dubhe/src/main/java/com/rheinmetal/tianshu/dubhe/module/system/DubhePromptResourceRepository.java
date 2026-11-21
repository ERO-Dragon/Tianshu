package com.rheinmetal.tianshu.dubhe.module.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.dubhe.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.storage.DubheStorageLayout;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DubhePromptResourceRepository {
    private static final String BUILTIN_TEXTS_RESOURCE = "/com/rheinmetal/tianshu/dubhe/prompts/ax_prompt_texts.json";
    private static final String[] BUILTIN_PROFILE_RESOURCES = {
            "/com/rheinmetal/tianshu/dubhe/prompts/general_ax.zh_cn.default.json"
    };

    private final DubheStorageLayout layout;
    private final DubheJsonStore jsonStore;

    public DubhePromptResourceRepository(DubheStorageLayout layout, DubheJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
        ensureExternalTextsCatalog();
        ensureExternalProfileCatalog();
    }

    public DubhePromptProfile loadProfile(DubhePromptTask task, DubhePromptLanguage language, String variant) {
        DubhePromptTask effectiveTask = task == null ? DubhePromptTask.GENERAL_AX : task;
        DubhePromptLanguage effectiveLanguage = language == null ? DubhePromptLanguage.ZH_CN : language;
        return loadProfile(new DubhePromptResourceKey(effectiveTask, effectiveLanguage, variant))
                .or(() -> loadProfile(new DubhePromptResourceKey(effectiveTask, DubhePromptLanguage.ZH_CN, variant)))
                .or(() -> loadProfile(new DubhePromptResourceKey(DubhePromptTask.GENERAL_AX, effectiveLanguage, variant)))
                .or(() -> loadProfile(new DubhePromptResourceKey(DubhePromptTask.GENERAL_AX, DubhePromptLanguage.ZH_CN, variant)))
                .orElseGet(() -> DubhePromptProfile.defaultFor(effectiveTask, effectiveLanguage));
    }

    public DubhePromptTexts loadTexts(DubhePromptLanguage language) {
        DubhePromptLanguage effectiveLanguage = language == null ? DubhePromptLanguage.BASE : language;
        Map<String, String> values = new LinkedHashMap<>();
        DubhePromptTexts.mergeCatalog(values, readBuiltinTexts(), DubhePromptLanguage.BASE);
        if (effectiveLanguage != DubhePromptLanguage.BASE) {
            DubhePromptTexts.mergeCatalog(values, readBuiltinTexts(), effectiveLanguage);
        }
        JsonObject external = readExternalTexts();
        DubhePromptTexts.mergeCatalog(values, external, DubhePromptLanguage.BASE);
        if (effectiveLanguage != DubhePromptLanguage.BASE) {
            DubhePromptTexts.mergeCatalog(values, external, effectiveLanguage);
        }
        return new DubhePromptTexts(effectiveLanguage, values);
    }

    private Optional<DubhePromptProfile> loadProfile(DubhePromptResourceKey key) {
        if (layout == null || jsonStore == null || key == null) {
            return Optional.empty();
        }
        Path path = layout.sharedRoot().resolve("prompts").resolve(key.fileName());
        return jsonStore.readObject(path).map(json -> toProfile(key, json));
    }

    private DubhePromptProfile toProfile(DubhePromptResourceKey key, JsonObject json) {
        DubhePromptProfile fallback = DubhePromptProfile.defaultFor(key.task(), key.language());
        String systemPrompt = DubhePromptProfile.readSystemPrompt(json, fallback.systemPrompt());
        List<String> sectionOrder = readStringArray(json, "sectionOrder");
        if (sectionOrder.isEmpty()) {
            sectionOrder = fallback.sectionOrder();
        }
        return new DubhePromptProfile(key.task(), key.language(), systemPrompt, sectionOrder);
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
        try (InputStream stream = DubhePromptResourceRepository.class.getResourceAsStream(resource)) {
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
        try (InputStream stream = DubhePromptResourceRepository.class.getResourceAsStream(BUILTIN_TEXTS_RESOURCE)) {
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
                try (InputStream stream = DubhePromptResourceRepository.class.getResourceAsStream(resource)) {
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
