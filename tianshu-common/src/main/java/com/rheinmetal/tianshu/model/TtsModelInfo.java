package com.rheinmetal.tianshu.model;

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
}
