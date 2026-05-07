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

    public static synchronized void invalidateTtsCache() {
        cachedCatalog = null;
    }

    public boolean isTtsModelDownloaded(TtsModelInfo info) {
        return checkTtsModelExists(resolveTtsModelDir(info), info);
    }

    public boolean isModelExists(ModelType modelType) {
        Path modelPath = getModelPath(modelType);
        if (modelPath == null) {
            return false;
        }
        return switch (modelType) {
            case ASR -> checkAsrModelExists(modelPath);
            case LLM -> checkLlmModelExists(modelPath);
            case TTS -> checkTtsModelExists(modelPath);
        };
    }

    public boolean checkAsrModelExists(Path dir) {
        return isDirectoryWithAnySuffix(dir, ".onnx", ".bin");
    }

    public boolean checkLlmModelExists(Path dir) {
        return isDirectoryWithAnySuffix(dir, ".gguf");
    }

    public boolean checkTtsModelExists(Path dir) {
        return checkTtsModelExists(dir, resolveTtsModelInfoByPath(dir));
    }

    public boolean checkPresetModelsExist(com.rheinmetal.tianshu.constant.VramTier tier) {
        Path asrDir = config.getAsrBasePath().resolve("model").resolve(com.rheinmetal.tianshu.constant.ModelPresets.getPresetAsrName(tier));
        Path llmDir = config.getLlmBasePath().resolve("model").resolve(com.rheinmetal.tianshu.constant.ModelPresets.getPresetLlmName(tier));
        Path ttsDir = config.getTtsBasePath().resolve("model").resolve(com.rheinmetal.tianshu.constant.ModelPresets.getPresetTtsName(tier));
        return checkAsrModelExists(asrDir) && checkLlmModelExists(llmDir) && checkTtsModelExists(ttsDir);
    }

    private boolean checkTtsModelExists(Path dir, TtsModelInfo info) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        if (info == null) {
            return isDirectoryWithAnySuffix(dir, ".onnx", ".bin", ".gguf");
        }
        String engineType = info.getEngineType();
        if ("zipvoice".equals(engineType)) {
            return Files.isRegularFile(dir.resolve("text_encoder.onnx"))
                    && Files.isRegularFile(dir.resolve("fm_decoder.onnx"));
        }
        if ("moss".equals(engineType)) {
            return containsFileNamed(dir, "browser_poc_manifest.json")
                    || isDirectoryWithAnySuffix(dir, ".onnx", ".bin", ".gguf");
        }
        if (info.modelFiles != null && !info.modelFiles.isEmpty()) {
            boolean hasAnyModelFile = info.modelFiles.stream().anyMatch(file -> Files.isRegularFile(dir.resolve(file)));
            if (!hasAnyModelFile) {
                return false;
            }
        } else if (!isDirectoryWithAnySuffix(dir, ".onnx", ".bin", ".gguf")) {
            return false;
        }
        if (info.needVocoder) {
            Path vocoderDir = dir.resolve("vocoders");
            if (!isDirectoryWithAnySuffix(vocoderDir, ".onnx")) {
                return false;
            }
        }
        if (info.voicesFile != null && !info.voicesFile.isBlank()) {
            if (!Files.isRegularFile(dir.resolve(info.voicesFile))) {
                return false;
            }
        }
        return true;
    }

    private Path resolveTtsModelDir(TtsModelInfo info) {
        if (info == null || info.name == null) {
            return null;
        }
        String modelDirName = "zipvoice".equals(info.getEngineType()) ? "ZipVoice" : info.name;
        return config.getTtsBasePath().resolve("model").resolve(modelDirName);
    }

    private TtsModelInfo resolveTtsModelInfoByPath(Path dir) {
        if (dir == null || dir.getFileName() == null) {
            return null;
        }
        String dirName = dir.getFileName().toString();
        for (TtsModelInfo info : loadTtsModelCatalog()) {
            if (info == null || info.name == null) {
                continue;
            }
            if ("zipvoice".equals(info.getEngineType())) {
                if ("ZipVoice".equalsIgnoreCase(dirName) || info.name.equalsIgnoreCase(dirName)) {
                    return info;
                }
            } else if (info.name.equalsIgnoreCase(dirName)) {
                return info;
            }
        }
        return null;
    }

    private boolean isDirectoryWithAnySuffix(Path dir, String... suffixes) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(path -> hasAnySuffix(path, suffixes));
        } catch (IOException e) {
            return false;
        }
    }

    private boolean containsFileNamed(Path dir, String fileName) {
        return Files.isDirectory(dir) && Files.isRegularFile(dir.resolve(fileName));
    }

    private boolean hasAnySuffix(Path path, String... suffixes) {
        String lower = path.getFileName().toString().toLowerCase();
        for (String suffix : suffixes) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private Path getModelPath(ModelType modelType) {
        return switch (modelType) {
            case ASR -> config.getAsrModelPath();
            case LLM -> config.getLlmModelPath();
            case TTS -> config.getTtsModelPath();
        };
    }
}
