package com.rheinmetal.tianshu.function.intake.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One a-priori entry of the ASR model catalog ({@code asr_model.json}).
 *
 * <p>Only catalog facts live here: identity, language, architecture, the files a runtime needs and the scores
 * the settings page orders by. Downloaded state and runtime readiness belong to the model service and the
 * engine, so they never turn into fields on this record.</p>
 */
public class AsrModelInfo {

    /** Score used when a catalog entry does not carry one: middle of the 1..10 scale. */
    private static final int DEFAULT_SCORE = 6;

    public String name;
    public String id;
    public long size;
    public List<String> lang;
    public String architecture;
    public boolean isStreaming;
    public Integer recognitionQualityScore;
    public Integer performanceScore;
    public Integer recommendationScore;
    public String downloadUrl;
    public String displayName;
    public Map<String, String> fileRoles;

    public String getDisplayName() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (!localKey().isBlank()) return localKey();
        return remoteRepoId().isBlank() ? "Unknown ASR model" : remoteRepoId();
    }

    /** The directory name of this model under the model root, and the key settings store. */
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

    public int getPerformanceScore() {
        return clampScore(performanceScore);
    }

    public int getRecognitionQualityScore() {
        return clampScore(recognitionQualityScore);
    }

    public int getRecommendationScore() {
        return clampScore(recommendationScore);
    }

    /** A catalog entry without an archive URL is downloaded file by file from its Hugging Face repository. */
    public boolean isHfDownload() {
        return downloadUrl == null || downloadUrl.isBlank();
    }

    /** Every file the runtime needs, in the order the catalog declares the roles. */
    public List<String> getAllRequiredFiles() {
        return List.copyOf(getFileRoles().values());
    }

    public List<String> getLang() {
        return lang != null ? lang : Collections.singletonList("zh");
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

    private int clampScore(Integer score) {
        int value = score == null ? DEFAULT_SCORE : score;
        if (value < 1) return 1;
        if (value > 10) return 10;
        return value;
    }
}
