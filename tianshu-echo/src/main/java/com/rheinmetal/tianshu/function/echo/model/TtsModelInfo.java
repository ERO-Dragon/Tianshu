package com.rheinmetal.tianshu.function.echo.model;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.Locale;
import java.util.Map;

public class TtsModelInfo {
    public static final String PERF_LOW = "low";
    public static final String PERF_MEDIUM = "medium";
    public static final String PERF_HIGH = "high";
    public static final String ENGINE_KOKORO = "kokoro";
    public static final String ENGINE_MATCHA = "matcha";
    public static final String ENGINE_MOSS = "moss";
    public static final String ENGINE_PIPER = "piper";
    public static final String ENGINE_POCKET = "pocket";
    public static final String ENGINE_VITS = "vits";
    private static final int DEFAULT_SCORE = 6;

    public String author;
    public String name;
    public String id;
    public long size;
    /**
     * Legacy recommendation flag. New TTS catalog entries should use the 10-point
     * recommendationScore field instead.
     */
    public boolean pinned;
    /**
     * Legacy 5-star quality hint. New TTS catalog entries should use
     * synthesisQualityScore instead.
     */
    public int rating;
    /**
     * Legacy performance cost class. New TTS catalog entries should use the
     * 10-point performanceScore field, where higher means more performance-friendly.
     */
    public String performance;
    public boolean needVocoder;
    public List<String> lang;
    public String engine;
    /**
     * Legacy coarse tier. New TTS catalog entries should use explicit scores.
     */
    public String tier;
    public List<String> modelFiles;
    public String dataDir;
    public List<String> lexiconFiles;
    public List<String> ruleFsts;
    public String voicesFile;
    public String downloadUri;
    /** Second Hugging Face repository a MOSS model needs for its audio tokenizer; empty for other engines. */
    public String codecRepoId;
    public String archiveSubDir;
    public String displayName;
    public String description;
    public Boolean supportsVoiceClone;
    public String defaultVoiceSample;
    /**
     * Voice a multi-speaker model speaks with until the player picks another index. The index that
     * sounds right belongs to the model, so it is catalog data rather than a caller's assumption.
     */
    public Integer defaultSpeakerId;
    /**
     * Voice per Unicode script ({@code LATIN}, {@code HAN}, ...) for a model that speaks several
     * scripts with different voices; text whose script is not listed keeps {@link #defaultSpeakerId}.
     */
    public Map<String, Integer> speakerIdByScript;
    public Integer synthesisQualityScore;
    public Integer performanceScore;
    public Integer recommendationScore;

    public String getEngineType() {
        if (engine != null && !engine.isBlank()) return engine;
        if (name == null) return ENGINE_VITS;
        String lower = name.toLowerCase();
        if (lower.contains(ENGINE_KOKORO)) return ENGINE_KOKORO;
        if (lower.contains(ENGINE_MATCHA)) return ENGINE_MATCHA;
        if (lower.contains(ENGINE_MOSS)) return ENGINE_MOSS;
        if (lower.contains(ENGINE_POCKET)) return ENGINE_POCKET;
        if (lower.contains(ENGINE_PIPER)) return ENGINE_PIPER;
        if (voicesFile != null && !voicesFile.isBlank()) return ENGINE_KOKORO;
        return ENGINE_VITS;
    }

    public String getDisplayName() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (name != null && !name.isBlank()) return name;
        return id != null ? id : "";
    }

    public String getDescription() {
        if (description != null && !description.isBlank()) return description;
        return "";
    }

    public int getSynthesisQualityScore() {
        return clampScore(synthesisQualityScore, legacyQualityScore());
    }

    public int getPerformanceScore() {
        return clampScore(performanceScore, legacyPerformanceScore());
    }

    public int getRecommendationScore() {
        return clampScore(recommendationScore, legacyRecommendationScore());
    }

    private int legacyQualityScore() {
        if ("premium".equalsIgnoreCase(tier)) return 8;
        if (rating >= 5) return 9;
        if (rating >= 4) return 8;
        if (rating >= 3) return 6;
        if (rating > 0) return 4;
        return DEFAULT_SCORE;
    }

    private int legacyPerformanceScore() {
        if (performance != null && !performance.isBlank()) {
            if (PERF_LOW.equalsIgnoreCase(performance)) return 9;
            if (PERF_MEDIUM.equalsIgnoreCase(performance)) return 6;
            if (PERF_HIGH.equalsIgnoreCase(performance)) return 4;
        }
        return switch (getEngineType()) {
            case ENGINE_MOSS -> 4;
            case ENGINE_MATCHA -> 6;
            default -> 8;
        };
    }

    private int legacyRecommendationScore() {
        if (pinned) return 9;
        if (rating >= 5) return 9;
        if (rating >= 4) return 8;
        if (rating >= 3) return 6;
        return DEFAULT_SCORE;
    }

    private int clampScore(Integer score, int fallback) {
        int value = score == null ? fallback : score;
        if (value < 1) return 1;
        if (value > 10) return 10;
        return value;
    }

    public boolean supportsVoiceClone() {
        if (supportsVoiceClone != null) return supportsVoiceClone;
        String engineType = getEngineType();
        return ENGINE_MOSS.equals(engineType) || ENGINE_POCKET.equals(engineType);
    }

    public boolean supportsSpeakerSelection() {
        return !supportsVoiceClone() && voicesFile != null && !voicesFile.isBlank();
    }

    /** Catalog-declared default voice, {@code 0} when the entry does not name one. */
    public int getDefaultSpeakerId() {
        return defaultSpeakerId == null ? 0 : Math.max(0, defaultSpeakerId);
    }

    /*
     * The catalog ships with the module that owns the models (tianshu-echo) and this reader lives beside
     * it, so a reader and its data follow the same owner; nothing in the kernel names a TTS model.
     */
    private static final String CATALOG_RESOURCE = "/com/rheinmetal/tianshu/model/catalog/tts-model.json";
    private static final Gson GSON = new Gson();
    private static List<TtsModelInfo> cachedCatalog = null;

    public static synchronized List<TtsModelInfo> loadCatalog() {
        if (cachedCatalog != null) return cachedCatalog;
        try (InputStream is = TtsModelInfo.class.getResourceAsStream(CATALOG_RESOURCE)) {
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

    /** Finds one catalog entry by name, the single lookup the model service and the settings page share. */
    public static TtsModelInfo findByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim();
        for (TtsModelInfo info : loadCatalog()) {
            if (info != null && info.name != null && info.name.equalsIgnoreCase(normalized)) {
                return info;
            }
        }
        return null;
    }

    public static boolean isModelDirectoryComplete(TtsModelInfo info, Path dir) {
        if (info == null || !Files.isDirectory(dir)) return false;
        String engineType = info.getEngineType();
        if (ENGINE_MOSS.equals(engineType)) {
            return mossManifestComplete(dir);
        }
        if (ENGINE_POCKET.equals(engineType)) {
            return filesPresent(dir, info.modelFiles);
        }
        if (info.modelFiles != null && !info.modelFiles.isEmpty()) {
            if (!containsAnyDeclaredFile(dir, info.modelFiles)) {
                return false;
            }
        } else if (!containsModelFile(dir)) {
            return false;
        }
        if (!containsFile(dir, "tokens", ".txt")) {
            return false;
        }
        return filesPresent(dir, info.lexiconFiles)
                && filesPresent(dir, info.ruleFsts)
                && filePresent(dir, info.voicesFile)
                && directoryPresent(dir, info.dataDir);
    }

    private static boolean containsAnyDeclaredFile(Path dir, List<String> files) {
        return files.stream()
                .filter(f -> f != null && !f.isBlank())
                .anyMatch(f -> Files.isRegularFile(dir.resolve(f)));
    }

    private static boolean filesPresent(Path dir, List<String> files) {
        if (files == null || files.isEmpty()) {
            return true;
        }
        return files.stream()
                .filter(f -> f != null && !f.isBlank())
                .allMatch(f -> Files.isRegularFile(dir.resolve(f)));
    }

    private static boolean filePresent(Path dir, String file) {
        return file == null || file.isBlank() || Files.isRegularFile(dir.resolve(file));
    }

    private static boolean directoryPresent(Path dir, String directory) {
        return directory == null || directory.isBlank() || Files.isDirectory(dir.resolve(directory));
    }

    private static boolean mossManifestComplete(Path dir) {
        Path manifest = findMossManifest(dir);
        if (manifest == null) {
            return false;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(manifest), JsonObject.class);
            if (root == null || !root.has("model_files") || !root.get("model_files").isJsonObject()) {
                return false;
            }
            JsonObject modelFiles = root.getAsJsonObject("model_files");
            // Every file the manifest names is required, not only the two meta descriptors: a directory
            // holding just those two was reported installed while the graphs they point at were missing.
            // Only the explicit file lists are checked; the manifest's other fields carry inline data rather
            // than paths. The flat-name fallback inside manifestRelativeValuePresent stays, because the codec
            // repository is downloaded flat into the same directory while the manifest names it as
            // "../MOSS-Audio-Tokenizer-Nano-ONNX/...".
            for (Map.Entry<String, JsonElement> declared : modelFiles.entrySet()) {
                JsonElement value = declared.getValue();
                // The runtime reads these entries as path strings; a value that is not a string names no file.
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                        || !manifestRelativeFilePresent(manifest, modelFiles, declared.getKey())) {
                    return false;
                }
            }
            if (!manifestListedFilesPresent(manifest, root)) {
                return false;
            }
            // Metadata alone cannot answer audio: at least one graph or weight file has to be on disk.
            return containsAnyFileWithExtension(dir, ".onnx", ".data");
        } catch (Exception e) {
            return false;
        }
    }

    /** Some manifests name their payload as a plain {@code files} list; when present, that list is required too. */
    private static boolean manifestListedFilesPresent(Path manifest, JsonObject root) {
        if (!root.has("files") || !root.get("files").isJsonArray()) {
            return true;
        }
        for (JsonElement declared : root.getAsJsonArray("files")) {
            // Entries this reader cannot read as a path are skipped instead of failing the install; the
            // model_files check and the on-disk graph check already cover the payload.
            if (declared.isJsonPrimitive() && declared.getAsJsonPrimitive().isString()
                    && !manifestRelativeValuePresent(manifest, declared.getAsString())) {
                return false;
            }
        }
        return true;
    }

    private static Path findMossManifest(Path dir) {
        List<Path> candidates = List.of(
                dir.resolve("browser_poc_manifest.json"),
                dir.resolve("MOSS-TTS-Nano-100M-ONNX").resolve("browser_poc_manifest.json"),
                dir.resolve("MOSS-TTS-Nano-ONNX-CPU").resolve("browser_poc_manifest.json")
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean manifestRelativeFilePresent(Path manifest, JsonObject modelFiles, String key) {
        if (!modelFiles.has(key) || modelFiles.get(key).isJsonNull()) {
            return false;
        }
        return manifestRelativeValuePresent(manifest, modelFiles.get(key).getAsString());
    }

    private static boolean manifestRelativeValuePresent(Path manifest, String relativeValue) {
        if (relativeValue == null || relativeValue.isBlank()) {
            return false;
        }
        Path relative = Path.of(relativeValue);
        Path resolved = manifest.getParent().resolve(relative).normalize();
        if (Files.isRegularFile(resolved)) {
            return true;
        }
        Path fileName = relative.getFileName();
        return fileName != null && containsExactFileName(manifest.getParent(), fileName.toString());
    }

    private static boolean containsAnyFileWithExtension(Path dir, String... extensions) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        List<String> suffixes = List.of(extensions);
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(path -> {
                if (!Files.isRegularFile(path)) {
                    return false;
                }
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return suffixes.stream().anyMatch(name::endsWith);
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean containsModelFile(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return Files.isRegularFile(p) && (name.endsWith(".onnx") || name.endsWith(".bin") || name.endsWith(".gguf"));
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean containsFile(Path dir, String keyword, String extension) {
        if (!Files.isDirectory(dir)) return false;
        String normalizedKeyword = keyword == null ? "" : keyword.toLowerCase();
        String normalizedExtension = extension == null ? "" : extension.toLowerCase();
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(p -> {
                if (!Files.isRegularFile(p)) {
                    return false;
                }
                String name = p.getFileName().toString().toLowerCase();
                return name.contains(normalizedKeyword) && name.endsWith(normalizedExtension);
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean containsExactFileName(Path dir, String fileName) {
        if (!Files.isDirectory(dir) || fileName == null || fileName.isBlank()) return false;
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(p -> Files.isRegularFile(p) && fileName.equals(p.getFileName().toString()));
        } catch (IOException e) {
            return false;
        }
    }
}
