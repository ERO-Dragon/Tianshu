package com.rheinmetal.tianshu.api;

import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.constant.VramTier;

import java.nio.file.Path;

public interface ITianshuConfig {

    boolean isAiEnabled();
    void setAiEnabled(boolean enabled);

    TriggerMode getTriggerMode();
    void setTriggerMode(TriggerMode mode);

    String getWakeWord();
    void setWakeWord(String word);

    VramTier getVramTier();
    void setVramTier(VramTier tier);

    int getCustomVramGB();

    int getAsrPort();
    int getLlmPort();
    int getTtsPort();

    String getCustomAsrName();
    void setCustomAsrName(String name);

    String getCustomLlmName();
    void setCustomLlmName(String name);

    String getCustomTtsName();
    void setCustomTtsName(String name);

    Path getRootPath();
    Path getAsrBasePath();
    Path getLlmBasePath();
    Path getTtsBasePath();
    Path getAsrModelPath();
    Path getLlmModelPath();
    Path getTtsModelPath();
    Path getLlmGgufFilePath();

    void save();
}
