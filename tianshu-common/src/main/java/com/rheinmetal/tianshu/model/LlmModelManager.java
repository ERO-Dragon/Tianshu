package com.rheinmetal.tianshu.model;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public class LlmModelManager {

    private static final String CATALOG_RESOURCE = "/com/rheinmetal/tianshu/constant/llm_models.json";
    private static final String EMBEDDING_CATALOG_RESOURCE = "/com/rheinmetal/tianshu/constant/llm_embedding_models.json";
    private static final Gson GSON = new Gson();
    private static List<LlmModelInfo> cachedCatalog = null;
    private static List<LlmModelInfo> cachedEmbeddingCatalog = null;

    public static synchronized List<LlmModelInfo> loadCatalog() {
        if (cachedCatalog != null) return cachedCatalog;
        cachedCatalog = loadResourceCatalog(CATALOG_RESOURCE);
        return cachedCatalog;
    }

    public static synchronized List<LlmModelInfo> loadEmbeddingCatalog() {
        if (cachedEmbeddingCatalog != null) return cachedEmbeddingCatalog;
        cachedEmbeddingCatalog = loadResourceCatalog(EMBEDDING_CATALOG_RESOURCE);
        return cachedEmbeddingCatalog;
    }

    private static List<LlmModelInfo> loadResourceCatalog(String resourcePath) {
        try (InputStream is = LlmModelManager.class.getResourceAsStream(resourcePath)) {
            if (is == null) return Collections.emptyList();
            try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<List<LlmModelInfo>>() {}.getType();
                List<LlmModelInfo> result = GSON.fromJson(reader, listType);
                return result != null ? result : Collections.emptyList();
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public static synchronized void invalidateCache() {
        cachedCatalog = null;
        cachedEmbeddingCatalog = null;
    }

    public static List<LlmModelInfo> getAllModels() {
        return loadCatalog();
    }

    public static List<LlmModelInfo> getAllEmbeddingModels() {
        return loadEmbeddingCatalog();
    }

    public static LlmModelInfo getDefaultEmbeddingModel(String language) {
        if (language != null && !language.isBlank()) {
            for (LlmModelInfo info : loadEmbeddingCatalog()) {
                if (info.supportsLanguage(language)) return info;
            }
        }
        List<LlmModelInfo> catalog = loadEmbeddingCatalog();
        return catalog.isEmpty() ? null : catalog.get(0);
    }

    public static LlmModelInfo getModelByName(String name) {
        if (name == null || name.isBlank()) return null;
        for (LlmModelInfo info : loadCatalog()) {
            if (name.equals(info.name)) return info;
        }
        return null;
    }

    public static LlmModelInfo getEmbeddingModelByName(String name) {
        if (name == null || name.isBlank()) return null;
        for (LlmModelInfo info : loadEmbeddingCatalog()) {
            if (name.equals(info.name)) return info;
        }
        return null;
    }

    public static boolean isModelDownloaded(LlmModelInfo info, Path llmModelBasePath) {
        if (info == null || llmModelBasePath == null) return false;
        Path modelDir = llmModelBasePath.resolve(info.name);
        if (!Files.isDirectory(modelDir)) return false;
        String modelFile = info.getModelFile();
        if (modelFile == null || modelFile.isBlank()) return false;
        return Files.isRegularFile(modelDir.resolve(modelFile));
    }
}
