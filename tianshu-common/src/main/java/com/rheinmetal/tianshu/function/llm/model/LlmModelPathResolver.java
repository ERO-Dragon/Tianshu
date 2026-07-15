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
    private final LlmConfiguration configuration;
    private final Function<String, LlmModelInfo> chatCatalog;
    private final Function<String, LlmModelInfo> embeddingCatalog;

    public LlmModelPathResolver(LlmConfiguration configuration) {
        this(configuration, LlmModelManager::getModelByName, LlmModelManager::getEmbeddingModelByName);
    }

    LlmModelPathResolver(
            LlmConfiguration configuration,
            Function<String, LlmModelInfo> chatCatalog,
            Function<String, LlmModelInfo> embeddingCatalog
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.chatCatalog = Objects.requireNonNull(chatCatalog, "chatCatalog");
        this.embeddingCatalog = Objects.requireNonNull(embeddingCatalog, "embeddingCatalog");
    }

    public Path resolveChatModel() {
        return resolve(configuration.getCustomLlmName(), chatCatalog);
    }

    public Path resolveEmbeddingModel() {
        return resolve(configuration.getLlmEmbeddingModelName(), embeddingCatalog);
    }

    public Path resolveMtpDraftModel() {
        return configuration.getLlmMtpDraftGgufFilePath();
    }

    public int chatContextSize() {
        LlmModelInfo info = chatCatalog.apply(normalize(configuration.getCustomLlmName()));
        return info == null ? 4096 : info.getContextSize();
    }

    public int promptTokenBudget() {
        LlmModelInfo info = chatCatalog.apply(normalize(configuration.getCustomLlmName()));
        return info == null ? 3000 : info.getPromptTokenBudget();
    }

    public int embeddingContextSize() {
        LlmModelInfo info = embeddingCatalog.apply(normalize(configuration.getLlmEmbeddingModelName()));
        return info == null ? 4096 : info.getContextSize();
    }

    private Path resolve(String selection, Function<String, LlmModelInfo> catalog) {
        String normalizedSelection = normalize(selection);
        if (normalizedSelection.isEmpty()) {
            return null;
        }
        Path modelRoot = configuration.getLlmBasePath().resolve("model").normalize();
        Path selectedPath = requireWithin(modelRoot, modelRoot.resolve(normalizedSelection).normalize());
        if (normalizedSelection.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            return selectedPath;
        }

        LlmModelInfo catalogInfo = catalog.apply(normalizedSelection);
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
