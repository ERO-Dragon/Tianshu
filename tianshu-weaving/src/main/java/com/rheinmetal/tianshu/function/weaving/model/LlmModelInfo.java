package com.rheinmetal.tianshu.function.weaving.model;

import java.util.Collections;
import java.util.List;

/**
 * One entry of a bundled catalog ({@code llm_models.json} / {@code llm_embedding_models.json}).
 *
 * <p>The cloud catalog carries sampling defaults keyed by provider model id; the embedding catalog
 * carries the file metadata the downloader and the RAG path resolver need. Both are <b>data</b>:
 * a catalog written by an older build may still carry keys this class no longer declares, and Gson
 * skips unknown keys, so an old file keeps loading untouched.</p>
 */
public class LlmModelInfo {

    /**
     * The context window assumed for an embedding catalog entry that declares none.
     *
     * <p>It is a ceiling the file was trained for, never the window the engine runs with.</p>
     */
    public static final int DEFAULT_CONTEXT_SIZE = 4096;

    public String name;
    public String displayName;
    public String repoId;
    public String hfFilePath;
    public String modelFile;
    public int contextSize;
    public long downloadSizeBytes;
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

    public long getDownloadSizeBytes() {
        return Math.max(0L, downloadSizeBytes);
    }

    public int getContextSize() {
        return contextSize > 0 ? contextSize : DEFAULT_CONTEXT_SIZE;
    }

    /** Sampling defaults for this model id; an entry without a block contributes nothing. */
    public SamplingSettings getSamplingSettings() {
        return sampling == null || sampling.standard == null
                ? SamplingSettings.empty()
                : sampling.standard;
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
    }

    /** The four sampling fields an OpenAI-compatible completion accepts. */
    public static class SamplingSettings {
        public Float temperature;
        public Float topP;
        public Float frequencyPenalty;
        public Float presencePenalty;

        private static SamplingSettings empty() {
            return new SamplingSettings();
        }

        public boolean isEmpty() {
            return temperature == null
                    && topP == null
                    && frequencyPenalty == null
                    && presencePenalty == null;
        }
    }
}
