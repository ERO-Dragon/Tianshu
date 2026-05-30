package com.rheinmetal.tianshu.api;

import com.rheinmetal.tianshu.constant.TriggerMode;

import java.nio.file.Files;
import java.nio.file.Path;

public interface ITianshuConfig {

    boolean isAiEnabled();
    void setAiEnabled(boolean enabled);

    default boolean isAsrEnabled() {
        return isAiEnabled();
    }

    default void setAsrEnabled(boolean enabled) {
        setAiEnabled(enabled);
    }

    TriggerMode getTriggerMode();
    void setTriggerMode(TriggerMode mode);

    String getWakeWord();
    void setWakeWord(String word);

    int getAsrPort();
    int getLlmPort();
    int getTtsPort();

    String getCustomAsrName();
    void setCustomAsrName(String name);

    default String getSelectedMicName() {
        return "";
    }

    default void setSelectedMicName(String name) {
    }

    default boolean isAsrRnnoiseEnabled() {
        return false;
    }

    default void setAsrRnnoiseEnabled(boolean enabled) {
    }

    default boolean isAsrVadEnabled() {
        return false;
    }

    default void setAsrVadEnabled(boolean enabled) {
    }

    default boolean isTtsEnabled() {
        return isAiEnabled();
    }

    default void setTtsEnabled(boolean enabled) {
        setAiEnabled(enabled);
    }

    String getCustomLlmName();
    void setCustomLlmName(String name);

    default boolean isLlmEnabled() {
        return isAiEnabled();
    }

    default void setLlmEnabled(boolean enabled) {
        setAiEnabled(enabled);
    }

    default int getLlmGpuLayerPercent() {
        return 80;
    }

    default void setLlmGpuLayerPercent(int percent) {
    }

    String getCustomTtsName();
    void setCustomTtsName(String name);

    Path getRootPath();
    Path getGameConfigDir();
    Path getAsrBasePath();
    Path getLlmBasePath();
    Path getTtsBasePath();
    Path getAsrModelPath();
    Path getLlmModelPath();
    Path getTtsModelPath();
    Path getLlmGgufFilePath();
    Path getVoiceLibraryPath();

    default Path getLlmEmbeddingModelPath() {
        String modelName = getLlmEmbeddingModelName();
        return getLlmBasePath().resolve("model").resolve(modelName == null ? "" : modelName);
    }

    default Path getLlmEmbeddingGgufFilePath() {
        String modelName = getLlmEmbeddingModelName();
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        Path modelDir = getLlmEmbeddingModelPath();
        if (Files.isDirectory(modelDir)) {
            com.rheinmetal.tianshu.model.LlmModelInfo catalogInfo = com.rheinmetal.tianshu.model.LlmModelManager.getEmbeddingModelByName(modelName);
            if (catalogInfo != null) {
                Path catalogFile = modelDir.resolve(catalogInfo.getModelFile());
                if (Files.isRegularFile(catalogFile)) {
                    return catalogFile;
                }
            }
            try (var stream = Files.list(modelDir)) {
                Path gguf = stream
                        .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".gguf"))
                        .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                        .findFirst()
                        .orElse(null);
                if (gguf != null) {
                    return gguf;
                }
            } catch (Exception ignored) {
            }
            return modelDir.resolve(catalogInfo != null ? catalogInfo.getModelFile() : modelName + ".gguf");
        }
        return null;
    }

    default String getLlmEmbeddingModelName() {
        return "";
    }

    default Path getLlmStaticRagPath() {
        return getLlmBasePath().resolve("rag").resolve("static");
    }

    default Path getLlmRagRootPath() {
        return getLlmBasePath().resolve("rag").resolve("root");
    }

    default boolean isLlmRagRootEnabled() {
        return true;
    }

    default Path getLlmMemoryRagPath() {
        return getLlmBasePath().resolve("rag").resolve("memory");
    }

    default int getLlmMemoryRagRefreshIntervalMs() {
        return 1000;
    }

    default int getLlmMemoryRagTokenBudget() {
        return 1000;
    }

    default int getLlmAXChatInputTokenBudget() {
        return 8000;
    }

    default int getLlmAXRecentRawChatTokenBudget() {
        return 4000;
    }

    default int getLlmAXShortTermChatTokenBudget() {
        return 1500;
    }

    default int getLlmAXUserConventionChatTokenBudget() {
        return 500;
    }

    default int getLlmAXDynamicRagChatTokenBudget() {
        return 500;
    }

    default int getLlmAXRecentRawKeepTokenTarget() {
        return 5000;
    }

    default int getLlmAXRecentRawKeepTokenMax() {
        return 8000;
    }

    default int getLlmAXShortTermCompressTokenTarget() {
        return 7000;
    }

    default int getLlmAXShortTermCompressTokenMax() {
        return 10000;
    }

    default int getLlmAXMaxRawEstimatedTokens() {
        return 28000;
    }

    default int getLlmAXMaxRawCharacters() {
        return 120000;
    }

    default int getLlmAXShortTermChatBlockLimit() {
        return 3;
    }

    default long getLlmAXConversationPauseMillis() {
        return 60000L;
    }

    default int getLlmAXLongTermMemoryMaxEntries() {
        return 2048;
    }

    default long getLlmAXLongTermMemoryTtlMillis() {
        return 1209600000L;
    }

    default int getLlmMaxQueueSize() {
        return 2;
    }

    default int getLlmTaskMaxQueueSize() {
        return 1;
    }

    default boolean isLlmTaskSuspendOnChatEnabled() {
        return true;
    }

    default int getLlmRequestTimeoutSeconds() {
        return 120;
    }

    default int getLlmStaticRagTopK() {
        return 4;
    }

    default int getLlmDynamicRagTopK() {
        return 4;
    }

    default int getLlmChatContextSize() {
        return getLlmContextSize();
    }

    default int getLlmTaskContextSize() {
        return getLlmContextSize();
    }

    default int getLlmEmbeddingContextSize() {
        return 4096;
    }

    default String getLlmCacheTypeK() {
        return "q8_0";
    }

    default String getLlmCacheTypeV() {
        return "q8_0";
    }

    default int getLlmContextSize() {
        return 4096;
    }

    void save();
}
