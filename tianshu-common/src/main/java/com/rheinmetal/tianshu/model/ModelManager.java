package com.rheinmetal.tianshu.model;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.rheinmetal.tianshu.api.ITianshuConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public class ModelManager {

    private static final Gson GSON = new Gson();
    private static List<TtsModelInfo> cachedCatalog = null;

    public enum ModelType {
        ASR("ASR模型"),
        LLM("LLM模型"),
        TTS("TTS模型");

        private final String name;

        ModelType(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    private final ITianshuConfig config;

    public ModelManager(ITianshuConfig config) {
        this.config = config;
    }

    public static synchronized List<TtsModelInfo> loadTtsModelCatalog() {
        if (cachedCatalog != null) return cachedCatalog;
        try (InputStream is = ModelManager.class.getResourceAsStream("/com/rheinmetal/tianshu/constant/sherpa-onnx-tts-models.json")) {
            if (is == null) return Collections.emptyList();
            try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<List<TtsModelInfo>>() {}.getType();
                cachedCatalog = GSON.fromJson(reader, listType);
                return cachedCatalog != null ? cachedCatalog : Collections.emptyList();
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public boolean isTtsModelDownloaded(TtsModelInfo info) {
        if (info == null || info.name == null) return false;
        Path modelDir = config.getTtsBasePath().resolve(info.name);
        if (!Files.exists(modelDir) || !Files.isDirectory(modelDir)) return false;
        try {
            return Files.list(modelDir).anyMatch(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".onnx") || name.endsWith(".bin");
            });
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isModelExists(ModelType modelType) {
        Path modelPath = getModelPath(modelType);
        if (modelPath == null) {
            return false;
        }
        return Files.exists(modelPath) && Files.isDirectory(modelPath);
    }

    public boolean checkAsrModelExists(Path dir) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return false;
        try {
            return Files.list(dir).anyMatch(p -> p.toString().endsWith(".onnx") || p.toString().endsWith(".bin"));
        } catch (IOException e) {
            return false;
        }
    }

    public boolean checkLlmModelExists(Path dir) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return false;
        try {
            return Files.list(dir).anyMatch(p -> p.toString().endsWith(".gguf"));
        } catch (IOException e) {
            return false;
        }
    }

    public boolean checkTtsModelExists(Path dir) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return false;
        try {
            return Files.list(dir).anyMatch(p -> p.toString().endsWith(".onnx") || p.toString().endsWith(".bin") || p.toString().endsWith(".gguf"));
        } catch (IOException e) {
            return false;
        }
    }

    public boolean checkPresetModelsExist(com.rheinmetal.tianshu.constant.VramTier tier) {
        Path asrDir = config.getAsrBasePath().resolve(com.rheinmetal.tianshu.constant.ModelPresets.getPresetAsrName(tier));
        Path llmDir = config.getLlmBasePath().resolve(com.rheinmetal.tianshu.constant.ModelPresets.getPresetLlmName(tier));
        Path ttsDir = config.getTtsBasePath().resolve(com.rheinmetal.tianshu.constant.ModelPresets.getPresetTtsName(tier));
        return checkAsrModelExists(asrDir) && checkLlmModelExists(llmDir) && checkTtsModelExists(ttsDir);
    }

    private Path getModelPath(ModelType modelType) {
        return switch (modelType) {
            case ASR -> config.getAsrModelPath();
            case LLM -> config.getLlmModelPath();
            case TTS -> config.getTtsModelPath();
        };
    }
}
