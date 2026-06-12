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
    public String downloadUrl;
    public String archiveSubDir;
    public String displayName;
    public String description;
    public Boolean supportsVoiceClone;
    public String defaultVoiceSample;
    public Integer synthesisQualityScore;
    public Integer performanceScore;
    public Integer recommendationScore;

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
        int quality = getSynthesisQualityScore();
        if (quality >= 9) return 5;
        if (quality >= 7) return 4;
        if (quality >= 5) return 3;
        if (quality >= 3) return 2;
        return pinned ? 5 : 0;
    }

    public String getPerformance() {
        if (performance != null && !performance.isBlank()) return performance;
        int score = getPerformanceScore();
        if (score >= 8) return PERF_LOW;
        if (score <= 4) return PERF_HIGH;
        return PERF_MEDIUM;
    }

    public int getSynthesisQualityScore() {
        return clampScore(synthesisQualityScore, legacyQualityScore());
    }

    public int getQualityScore() {
        return getSynthesisQualityScore();
    }

    public int getPerformanceScore() {
        return clampScore(performanceScore, legacyPerformanceScore());
    }

    public int getRecommendationScore() {
        return clampScore(recommendationScore, legacyRecommendationScore());
    }

    public int getValueScore() {
        return getRecommendationScore() * 100 + getSynthesisQualityScore() * 10 + getPerformanceScore();
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
            case "moss" -> 4;
            case "matcha", "zipvoice" -> 6;
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

    private static final String CATALOG_RESOURCE = "/com/rheinmetal/tianshu/constant/tts-model.json";
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
