package com.rheinmetal.tianshu.model;

import java.util.Collections;
import java.util.List;

/**
 * One entry of a bundled GGUF model catalog ({@code llm_models.json} / {@code llm_embedding_models.json}).
 *
 * <p>The catalog is <b>data</b>, not code: a catalog shipped or cached by an older build may still carry
 * keys this class no longer declares (the retired local-chat MTP pair {@code mtpDraftModelFile} /
 * {@code mtpDraftHfFilePath} is the current example). Parsing is Gson reflection over these public
 * fields, and Gson skips unknown keys, so such a catalog keeps loading untouched. Do not add a strict
 * adapter or unknown-key rejection here — an old data file must never fail to parse.</p>
 */
public class LlmModelInfo {

    public String name;
    public String displayName;
    public String repoId;
    public String hfFilePath;
    public String modelFile;
    public String series;
    public String descriptionKey;
    public String description;
    public String thinkingCapability;
    public long downloadSizeBytes;
    public long estimatedVramBytes;
    public int contextSize;
    public int promptTokenBudget;
    public SamplingProfiles sampling;
    public List<String> lang;

    public String getDisplayName() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (name != null && !name.isBlank()) return name;
        return repoId != null ? repoId : "";
    }

    public String getModelFile() {
        if (modelFile != null && !modelFile.isBlank()) return modelFile;
        return name != null ? name + ".gguf" : "model.gguf";
    }

    public String getSeries() {
        return series == null ? "" : series.trim();
    }

    public String getDescriptionKey() {
        return descriptionKey == null ? "" : descriptionKey.trim();
    }

    public String getDescription() {
        return description == null ? "" : description.trim();
    }

    public String getThinkingCapability() {
        return thinkingCapability == null ? "" : thinkingCapability.trim();
    }

    public long getDownloadSizeBytes() {
        return Math.max(0L, downloadSizeBytes);
    }

    public long getEstimatedVramBytes() {
        return Math.max(0L, estimatedVramBytes);
    }

    public int getContextSize() {
        return contextSize > 0 ? contextSize : 4096;
    }

    public int getPromptTokenBudget() {
        return promptTokenBudget > 0 ? promptTokenBudget : 3000;
    }

    public SamplingSettings getSamplingSettings(boolean thinking) {
        if (sampling == null) {
            return SamplingSettings.empty();
        }
        SamplingSettings selected = thinking ? sampling.thinking : sampling.standard;
        if (selected != null && !selected.isEmpty()) {
            return selected;
        }
        SamplingSettings fallback = thinking ? sampling.standard : sampling.thinking;
        return fallback == null ? SamplingSettings.empty() : fallback;
    }

    public List<String> getLang() {
        return lang != null ? Collections.unmodifiableList(lang) : Collections.emptyList();
    }

    public boolean supportsLanguage(String language) {
        if (lang == null || lang.isEmpty()) return true;
        return lang.contains(language);
    }

    public static class SamplingProfiles {
        public SamplingSettings standard;
        public SamplingSettings thinking;
    }

    public static class SamplingSettings {
        public Float temperature;
        public Integer topK;
        public Float topP;
        public Float minP;
        public Float penaltyRepeat;
        public Float penaltyFreq;
        public Float penaltyPresent;
        public Integer penaltyLastN;

        private static SamplingSettings empty() {
            return new SamplingSettings();
        }

        public boolean isEmpty() {
            return temperature == null
                    && topK == null
                    && topP == null
                    && minP == null
                    && penaltyRepeat == null
                    && penaltyFreq == null
                    && penaltyPresent == null
                    && penaltyLastN == null;
        }
    }
}
