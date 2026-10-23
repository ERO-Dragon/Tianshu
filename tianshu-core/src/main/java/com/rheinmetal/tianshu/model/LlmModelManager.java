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

/**
 * The two bundled catalogs: the cloud models this build ships sampling defaults for, and the local RAG
 * vector models it can download.
 *
 * <p>{@code llm_models.json} is not a download list. Chat runs in the cloud, so the only thing a chat
 * model needs from a catalog is its sampling profile, and that is what its entries carry — keyed by cloud
 * model id, so the request preparer can start a request from the model's own defaults. The local GGUF
 * chat entries are gone with the local chat lane; {@code llm_embedding_models.json} is the catalog that
 * still describes files on disk, and it is the one the settings page downloads and the RAG path resolves.
 */
public class LlmModelManager {

    /*
     * Both catalogs ship with the module that owns the models (tianshu-weaving), not with this reader: the
     * reader is shared by the owning module, the settings page and the platform configuration, so it stays
     * in the kernel while the data follows its owner. The path mirrors this package, so the kernel never
     * has to name a feature package.
     */
    private static final String CATALOG_RESOURCE = "/com/rheinmetal/tianshu/model/catalog/llm_models.json";
    private static final String EMBEDDING_CATALOG_RESOURCE = "/com/rheinmetal/tianshu/model/catalog/llm_embedding_models.json";
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
                return parseCatalog(reader);
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Parses a catalog array. Gson ignores keys the model class does not declare, which is what makes a
     * catalog from an older build (still carrying a since-removed field such as the retired
     * {@code mtpDraftModelFile}) load without error. Kept as its own seam so the tolerance is testable
     * against a string, not only against the bundled resource.
     */
    static List<LlmModelInfo> parseCatalog(java.io.Reader reader) {
        Type listType = new TypeToken<List<LlmModelInfo>>() {}.getType();
        List<LlmModelInfo> result = GSON.fromJson(reader, listType);
        return result != null ? result : Collections.emptyList();
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
