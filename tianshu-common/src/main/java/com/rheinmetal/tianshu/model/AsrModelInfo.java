package com.rheinmetal.tianshu.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AsrModelInfo {

    public static final String TIER_LOW = "LOW";
    public static final String TIER_MID = "MID";
    public static final String TIER_HIGH = "HIGH";

    public static final String TYPE_TRANSDUCER = "TRANSDUCER";
    public static final String TYPE_PARAFORMER = "PARAFORMER";
    public static final String TYPE_WHISPER = "WHISPER";
    public static final String TYPE_NEMO = "NEMO";
    public static final String TYPE_CTC = "CTC";
    public static final String TYPE_WENET = "WENET";
    public static final String TYPE_TELESPEECH = "TELESPEECH";
    public static final String TYPE_SENSEVOICE = "SENSEVOICE";
    public static final String TYPE_MOONSHINE = "MOONSHINE";
    public static final String TYPE_DOLPHIN = "DOLPHIN";
    public static final String TYPE_QWEN3_ASR = "QWEN3_ASR";
    public static final String TYPE_FUNASR_NANO = "FUNASR_NANO";
    public static final String TYPE_OTHER = "OTHER";

    public String author;
    public String name;
    public String id;
    public long size;
    public List<String> lang;
    public List<String> modelFiles;
    public List<String> lexiconFiles;
    public boolean pinned;
    public boolean isStreaming;
    public String modelType;
    public boolean supportHotwords;
    public String recommendedTier;
    public String downloadUrl;
    public boolean isInt8Available;
    public String qualityTier;
    public String performanceClass;
    public String displayName;

    public String getDisplayName() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (name != null && !name.isBlank()) return name;
        return id != null ? id : "未知模型";
    }

    public String getModelType() {
        if (modelType != null && !modelType.isBlank()) return modelType;
        return TYPE_TRANSDUCER;
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
        return tierToScore(getQualityTier());
    }

    public int getPerformanceScore() {
        return tierToScore(getPerformanceClass());
    }

    public int getValueScore() {
        return getQualityScore() * 10 - getPerformanceScore() * 4;
    }

    public boolean isHfDownload() {
        return downloadUrl == null || downloadUrl.isBlank();
    }

    public boolean isArchiveDownload() {
        return downloadUrl != null && !downloadUrl.isBlank();
    }

    public List<String> getAllRequiredFiles() {
        List<String> all = new ArrayList<>();
        if (modelFiles != null) all.addAll(modelFiles);
        if (lexiconFiles != null) all.addAll(lexiconFiles);
        return Collections.unmodifiableList(all);
    }

    public List<String> getLang() {
        return lang != null ? lang : Collections.singletonList("zh");
    }

    public boolean isTransducer() {
        return TYPE_TRANSDUCER.equalsIgnoreCase(getModelType());
    }

    public boolean isParaformer() {
        return TYPE_PARAFORMER.equalsIgnoreCase(getModelType());
    }

    public boolean usesEncoderDecoderOnly() {
        return TYPE_PARAFORMER.equalsIgnoreCase(getModelType())
                || TYPE_CTC.equalsIgnoreCase(getModelType())
                || TYPE_WENET.equalsIgnoreCase(getModelType())
                || TYPE_TELESPEECH.equalsIgnoreCase(getModelType());
    }

    public boolean usesSingleModelFile() {
        return TYPE_WHISPER.equalsIgnoreCase(getModelType())
                || TYPE_NEMO.equalsIgnoreCase(getModelType())
                || TYPE_SENSEVOICE.equalsIgnoreCase(getModelType())
                || TYPE_MOONSHINE.equalsIgnoreCase(getModelType())
                || TYPE_DOLPHIN.equalsIgnoreCase(getModelType())
                || TYPE_QWEN3_ASR.equalsIgnoreCase(getModelType())
                || TYPE_FUNASR_NANO.equalsIgnoreCase(getModelType())
                || TYPE_OTHER.equalsIgnoreCase(getModelType());
    }

    public boolean isEngineSupported() {
        return isTransducer() || usesEncoderDecoderOnly();
    }

    private int tierToScore(String tier) {
        if (TIER_HIGH.equalsIgnoreCase(tier)) return 3;
        if (TIER_MID.equalsIgnoreCase(tier)) return 2;
        if (TIER_LOW.equalsIgnoreCase(tier)) return 1;
        return 2;
    }
}
