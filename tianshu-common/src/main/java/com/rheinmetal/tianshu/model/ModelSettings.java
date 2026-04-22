package com.rheinmetal.tianshu.model;

import com.rheinmetal.tianshu.api.IGameEnvironment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ModelSettings {

    private static final String SETTINGS_FILE = "model-settings.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class AsrSettings {
        public List<String> hotwords = new ArrayList<>();
        public double hotwordsScore = 1.5;
    }

    public static class TtsSettings {
        public double speed = 1.0;
        public int speakerId = 0;
        public String selectedVoiceSample = "";
    }

    public static class LlmSettings {
        public String systemPrompt = "";
        public double temperature = 0.7;
    }

    public static AsrSettings loadAsrSettings(Path modelDir) {
        return load(modelDir, AsrSettings.class, new AsrSettings());
    }

    public static TtsSettings loadTtsSettings(Path modelDir) {
        return load(modelDir, TtsSettings.class, new TtsSettings());
    }

    public static LlmSettings loadLlmSettings(Path modelDir) {
        return load(modelDir, LlmSettings.class, new LlmSettings());
    }

    public static void saveAsrSettings(Path modelDir, AsrSettings settings) {
        save(modelDir, settings);
    }

    public static void saveTtsSettings(Path modelDir, TtsSettings settings) {
        save(modelDir, settings);
    }

    public static void saveLlmSettings(Path modelDir, LlmSettings settings) {
        save(modelDir, settings);
    }

    private static <T> T load(Path modelDir, Class<T> clazz, T defaultValue) {
        Path file = modelDir.resolve(SETTINGS_FILE);
        if (!Files.exists(file)) {
            return defaultValue;
        }
        try {
            String json = Files.readString(file);
            T result = GSON.fromJson(json, clazz);
            return result != null ? result : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static <T> void save(Path modelDir, T settings) {
        try {
            if (!Files.exists(modelDir)) {
                Files.createDirectories(modelDir);
            }
            Path file = modelDir.resolve(SETTINGS_FILE);
            String json = GSON.toJson(settings);
            Files.writeString(file, json);
        } catch (IOException e) {
            // callers handle this
        }
    }
}
