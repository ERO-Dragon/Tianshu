package com.rheinmetal.tianshu.model;

import com.rheinmetal.tianshu.config.Config;
import com.rheinmetal.tianshu.config.Config.VramTier;

import java.nio.file.Files;
import java.nio.file.Path;

public class ModelManager {

    // 模型类型枚举
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

    public ModelManager() {
    }

    // 检查模型是否存在
    public boolean isModelExists(ModelType modelType) {
        Path modelPath = getModelPath(modelType);
        if (modelPath == null) {
            return false;
        }

        return Files.exists(modelPath) && Files.isDirectory(modelPath);
    }

    // 获取模型路径
    private Path getModelPath(ModelType modelType) {

        switch (modelType) {
            case ASR:
                return Config.getAsrModelPath();
            case LLM:
                return Config.getLlmModelPath();
            case TTS:
                return Config.getTtsModelPath();
            default:
                return null;
        }
    }
}