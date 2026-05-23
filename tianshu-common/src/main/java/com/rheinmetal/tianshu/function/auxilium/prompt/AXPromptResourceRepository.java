package com.rheinmetal.tianshu.function.auxilium.prompt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AXPromptResourceRepository {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;

    public AXPromptResourceRepository(AXStorageLayout layout, AXJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
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
}
