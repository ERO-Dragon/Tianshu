package com.rheinmetal.tianshu.model;

import java.util.List;

public class TtsModelInfo {
    public String author;
    public String name;
    public String id;
    public long size;
    public boolean pinned;
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

    public static final String TIER_STANDARD = "standard";
    public static final String TIER_PREMIUM = "premium";

    public String getTier() {
        if (tier != null && !tier.isBlank()) return tier;
        if ("moss".equals(getEngineType())) return TIER_PREMIUM;
        return TIER_STANDARD;
    }

    public String getEngineType() {
        if (engine != null && !engine.isBlank()) return engine.toLowerCase();
        if (name != null && name.toLowerCase().contains("moss")) return "moss";
        if (name != null && name.toLowerCase().contains("zipvoice")) return "zipvoice";
        if (needVocoder) return "matcha";
        if (voicesFile != null && !voicesFile.isBlank()) return "kokoro";
        if ((name != null && name.toLowerCase().contains("piper")) || 
            (author != null && author.toLowerCase().contains("rhasspy"))) {
            return "piper";
        }
        return "vits";
    }
}
