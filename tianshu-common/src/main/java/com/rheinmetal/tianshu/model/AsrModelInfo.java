package com.rheinmetal.tianshu.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class AsrModelInfo {

    public static final String TIER_LOW = "LOW";
    public static final String TIER_MID = "MID";
    public static final String TIER_HIGH = "HIGH";
    private static final int DEFAULT_SCORE = 6;

    public String author;
    public String name;
    public String id;
    public long size;
    public List<String> lang;
    /**
     * Legacy flat file list. New ASR catalog entries should use fileRoles as the
     * single source of model files, because each runtime file has a semantic role.
     */
    public List<String> modelFiles;
    public boolean pinned;
    public String architecture;
    public boolean isStreaming;
    public Integer recognitionQualityScore;
    public Integer performanceScore;
    public Integer recommendationScore;
    /**
     * Legacy score tiers. New ASR catalog entries should use the 10-point score
     * fields above.
     */
    public String recommendedTier;
    public String downloadUrl;
    public String qualityTier;
    public String performanceClass;
    public String displayName;
    public List<String> tags;
    public Map<String, String> fileRoles;

    public String getDisplayName() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (localKey() != null && !localKey().isBlank()) return localKey();
        return remoteRepoId() != null ? remoteRepoId() : "Unknown ASR model";
    }

    public String localKey() {
        return name == null ? "" : name.trim();
    }

    public String remoteRepoId() {
        return id == null ? "" : id.trim();
    }

    public String architecture() {
        return architecture == null ? "" : architecture.trim();
    }

    public boolean isStreamingModel() {
        return isStreaming;
    }

    public String getRecommendedTier() {
        if (recommendedTier != null && !recommendedTier.isBlank()) return recommendedTier;
        return TIER_MID;
    }

    public String getQualityTier() {
        if (qualityTier != null && !qualityTier.isBlank()) return qualityTier;
        return TIER_MID;
    }

    public String getPerformanceClass() {
        if (performanceClass != null && !performanceClass.isBlank()) return performanceClass;
        return TIER_MID;
    }

    public int getQualityScore() {
        return getRecognitionQualityScore();
    }

    public int getPerformanceScore() {
        return clampScore(performanceScore, tierToScore(getPerformanceClass()));
    }

    public int getRecognitionQualityScore() {
        return clampScore(recognitionQualityScore, tierToScore(getQualityTier()));
    }

    public int getRecommendationScore() {
        return clampScore(recommendationScore, tierToScore(getRecommendedTier()));
    }

    public int getValueScore() {
        return getRecommendationScore() * 100 + getRecognitionQualityScore() * 10 + getPerformanceScore();
    }

    public boolean isHfDownload() {
        return downloadUrl == null || downloadUrl.isBlank();
    }

    public List<String> getAllRequiredFiles() {
        LinkedHashSet<String> files = new LinkedHashSet<>();
        files.addAll(getFileRoles().values());
        files.addAll(getLegacyModelFiles());
        return List.copyOf(files);
    }

    public List<String> getModelFiles() {
        return getAllRequiredFiles();
    }

    public List<String> getLang() {
        return lang != null ? lang : Collections.singletonList("zh");
    }

    public List<String> getTags() {
        if (tags == null || tags.isEmpty()) return Collections.emptyList();
        return tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public Map<String, String> getFileRoles() {
        if (fileRoles == null || fileRoles.isEmpty()) return Collections.emptyMap();
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fileRoles.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String role = entry.getKey().trim().toLowerCase();
            String file = entry.getValue().trim();
            if (!role.isBlank() && !file.isBlank()) {
                normalized.put(role, file);
            }
        }
        return normalized;
    }

    private List<String> getLegacyModelFiles() {
        if (modelFiles == null || modelFiles.isEmpty()) return Collections.emptyList();
        return modelFiles.stream()
                .filter(file -> file != null && !file.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private int tierToScore(String tier) {
        if (TIER_HIGH.equalsIgnoreCase(tier)) return 8;
        if (TIER_MID.equalsIgnoreCase(tier)) return 6;
        if (TIER_LOW.equalsIgnoreCase(tier)) return 4;
        return DEFAULT_SCORE;
    }

    private int clampScore(Integer score, int fallback) {
        int value = score == null ? fallback : score;
        if (value < 1) return 1;
        if (value > 10) return 10;
        return value;
    }
}
