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
    public List<String> modelFiles;
    public String dataDir;
    public List<String> lexiconFiles;
    public List<String> ruleFsts;
    public String voicesFile;

    public String getEngineType() {
        if (engine != null && !engine.isBlank()) return engine.toLowerCase();
        if (needVocoder) return "matcha";
        if (voicesFile != null && !voicesFile.isBlank()) return "kokoro";
        if ((name != null && name.toLowerCase().contains("piper")) || 
            (author != null && author.toLowerCase().contains("rhasspy"))) { // Piper 主要是 Rhasspy 团队做的
            return "piper";
        }
        return "vits";
    }
}
