package com.rheinmetal.tianshu.function.llm.model;

import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.model.LlmModelInfo;
import com.rheinmetal.tianshu.model.LlmModelManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

public final class LlmModelPathResolver {
    /** Fallback window when the embedding catalog has no entry for the selected model. */
    private static final int DEFAULT_EMBEDDING_CONTEXT_SIZE = 4096;

    private final LlmConfiguration configuration;
    private final Function<String, LlmModelInfo> embeddingCatalog;

    public LlmModelPathResolver(LlmConfiguration configuration) {
        this(configuration, LlmModelManager::getEmbeddingModelByName);
    }

    LlmModelPathResolver(
            LlmConfiguration configuration,
            Function<String, LlmModelInfo> embeddingCatalog
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.embeddingCatalog = Objects.requireNonNull(embeddingCatalog, "embeddingCatalog");
    }

    public Path resolveEmbeddingModel() {
        return resolve(configuration.getLlmEmbeddingModelName());
    }

    public int embeddingContextSize() {
        LlmModelInfo info = embeddingCatalog.apply(normalize(configuration.getLlmEmbeddingModelName()));
        return info == null ? DEFAULT_EMBEDDING_CONTEXT_SIZE : info.getContextSize();
    }

    private Path resolve(String selection) {
        String normalizedSelection = normalize(selection);
        if (normalizedSelection.isEmpty()) {
            return null;
        }
        Path modelRoot = configuration.getLlmBasePath().resolve("model").normalize();
        Path selectedPath = requireWithin(modelRoot, modelRoot.resolve(normalizedSelection).normalize());
        if (normalizedSelection.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            return selectedPath;
        }

        LlmModelInfo catalogInfo = embeddingCatalog.apply(normalizedSelection);
        if (Files.isDirectory(selectedPath)) {
            Path catalogFile = selectedPath.resolve(catalogFileName(catalogInfo)).normalize();
            requireWithin(selectedPath, catalogFile);
            if (catalogInfo != null && Files.isRegularFile(catalogFile)) {
                return catalogFile;
            }
            Path discovered = firstGguf(selectedPath);
            if (discovered != null) {
                return discovered;
            }
        }
        String fallbackName = catalogInfo == null ? normalizedSelection + ".gguf" : catalogFileName(catalogInfo);
        return requireWithin(selectedPath, selectedPath.resolve(fallbackName).normalize());
    }

    private Path firstGguf(Path directory) {
        try (var files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
                    .min(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .orElse(null);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to inspect LLM model directory: " + directory, exception);
        }
    }

    private String catalogFileName(LlmModelInfo info) {
        return info == null ? "model.gguf" : info.getModelFile();
    }

    private Path requireWithin(Path root, Path candidate) {
        Path normalizedRoot = root.normalize();
        Path normalizedCandidate = candidate.normalize();
        if (!normalizedCandidate.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Model selection escapes the LLM model root");
        }
        return normalizedCandidate;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
