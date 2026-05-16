package com.rheinmetal.tianshu.function.assistant.prompt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.assistant.storage.AssistantJsonStore;
import com.rheinmetal.tianshu.function.assistant.storage.AssistantStorageLayout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AssistantPromptResourceRepository {
    private final AssistantStorageLayout layout;
    private final AssistantJsonStore jsonStore;

    public AssistantPromptResourceRepository(AssistantStorageLayout layout, AssistantJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public AssistantPromptProfile loadProfile(AssistantPromptTask task, AssistantPromptLanguage language, String variant) {
        AssistantPromptTask effectiveTask = task == null ? AssistantPromptTask.GENERAL_ASSISTANT : task;
        AssistantPromptLanguage effectiveLanguage = language == null ? AssistantPromptLanguage.ZH_CN : language;
        return loadProfile(new AssistantPromptResourceKey(effectiveTask, effectiveLanguage, variant))
                .or(() -> loadProfile(new AssistantPromptResourceKey(effectiveTask, AssistantPromptLanguage.ZH_CN, variant)))
                .or(() -> loadProfile(new AssistantPromptResourceKey(AssistantPromptTask.GENERAL_ASSISTANT, effectiveLanguage, variant)))
                .or(() -> loadProfile(new AssistantPromptResourceKey(AssistantPromptTask.GENERAL_ASSISTANT, AssistantPromptLanguage.ZH_CN, variant)))
                .orElseGet(() -> AssistantPromptProfile.defaultFor(effectiveTask, effectiveLanguage));
    }

    private Optional<AssistantPromptProfile> loadProfile(AssistantPromptResourceKey key) {
        if (layout == null || jsonStore == null || key == null) {
            return Optional.empty();
        }
        Path path = layout.sharedRoot().resolve("prompts").resolve(key.fileName());
        return jsonStore.readObject(path).map(json -> toProfile(key, json));
    }

    private AssistantPromptProfile toProfile(AssistantPromptResourceKey key, JsonObject json) {
        AssistantPromptProfile fallback = AssistantPromptProfile.defaultFor(key.task(), key.language());
        String identity = readString(json, "identity", fallback.identity());
        String behaviorRules = readString(json, "behaviorRules", fallback.behaviorRules());
        List<String> sectionOrder = readStringArray(json, "sectionOrder");
        if (sectionOrder.isEmpty()) {
            sectionOrder = fallback.sectionOrder();
        }
        return new AssistantPromptProfile(key.task(), key.language(), identity, behaviorRules, sectionOrder);
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
