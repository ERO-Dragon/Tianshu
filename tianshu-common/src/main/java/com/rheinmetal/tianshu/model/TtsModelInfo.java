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

public class TtsModelInfo {
    public static final String TIER_STANDARD = "standard";
    public static final String TIER_PREMIUM = "premium";
    public static final String PERF_LOW = "low";
    public static final String PERF_MEDIUM = "medium";
    public static final String PERF_HIGH = "high";

    public String author;
    public String name;
    public String id;
    public long size;
    public boolean pinned;
    public int rating;
    public String performance;
    public boolean needVocoder;
    public List<String> lang;
    public String engine;
    public String tier;
    public List<String> modelFiles;
    public String dataDir;
    public List<String> lexiconFiles;
    public List<String> ruleFsts;
    public String voicesFile;
    public String downloadUrl;
    public String archiveSubDir;
    public String displayName;
    public String description;
    public Boolean supportsVoiceClone;
    public String defaultVoiceSample;

    public String getEngineType() {
        if (engine != null && !engine.isBlank()) return engine;
        if (name == null) return "vits";
        String lower = name.toLowerCase();
        if (lower.contains("kokoro")) return "kokoro";
        if (lower.contains("matcha")) return "matcha";
        if (lower.contains("moss")) return "moss";
        if (lower.contains("zipvoice")) return "zipvoice";
        if (lower.contains("piper")) return "piper";
        if (voicesFile != null && !voicesFile.isBlank()) return "kokoro";
        return "vits";
    }

    public String getTier() {
        if (tier != null && !tier.isBlank()) return tier;
        if ("moss".equals(getEngineType())) return TIER_PREMIUM;
        return TIER_STANDARD;
    }

    public String getDisplayName() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (name != null && !name.isBlank()) return name;
        return id != null ? id : "未知模型";
    }

    public String getDescription() {
        if (description != null && !description.isBlank()) return description;
        return switch (getEngineType()) {
            case "moss" -> "自回归高自然度语音合成";
            case "zipvoice" -> "支持参考音频克隆的 SherpaOnnx 模型";
            case "kokoro" -> "多音色 SherpaOnnx 模型";
            case "matcha" -> "需要声码器的轻量 TTS 模型";
            case "piper" -> "轻量快速的 SherpaOnnx 模型";
            default -> "通用 TTS 模型";
        };
    }

    public int getRating() {
        if (rating > 0) return Math.min(5, Math.max(1, rating));
        return pinned ? 5 : 0;
    }

    public String getPerformance() {
        if (performance != null && !performance.isBlank()) return performance;
        return switch (getEngineType()) {
            case "moss" -> PERF_HIGH;
            case "matcha" -> PERF_MEDIUM;
            default -> PERF_LOW;
        };
    }

    public String getPerformanceLabel() {
        return switch (getPerformance()) {
            case PERF_LOW -> "低";
            case PERF_MEDIUM -> "中";
            case PERF_HIGH -> "高";
            default -> "未知";
        };
    }

    public boolean supportsVoiceClone() {
        if (supportsVoiceClone != null) return supportsVoiceClone;
        String engineType = getEngineType();
        return "moss".equals(engineType) || "zipvoice".equals(engineType);
    }

    public boolean supportsSpeakerSelection() {
        return !supportsVoiceClone() && voicesFile != null && !voicesFile.isBlank();
    }

    private static final String CATALOG_RESOURCE = "/com/rheinmetal/tianshu/constant/sherpa-onnx-tts-models.json";
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

    public static synchronized void invalidateCatalogCache() {
        cachedCatalog = null;
    }

    public static boolean isModelDownloaded(TtsModelInfo info, Path ttsBasePath) {
        if (info == null || ttsBasePath == null) return false;
        String modelDirName = "zipvoice".equals(info.getEngineType()) ? "ZipVoice" : info.name;
        Path dir = ttsBasePath.resolve("model").resolve(modelDirName);
        if (!Files.isDirectory(dir)) return false;
        if (info.modelFiles != null && !info.modelFiles.isEmpty()) {
            return info.modelFiles.stream()
                    .filter(f -> f != null && !f.isBlank())
                    .anyMatch(f -> Files.isRegularFile(dir.resolve(f)));
        }
        if ("moss".equals(info.getEngineType())) {
            return Files.isRegularFile(dir.resolve("browser_poc_manifest.json"))
                    || containsModelFile(dir);
        }
        return containsModelFile(dir);
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
}
