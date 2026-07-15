package com.rheinmetal.tianshu.client.gui.auxilium;

public interface AxSettingsAccess {
    boolean assistantEnabled();
    void setAxEnabled(boolean enabled);
    boolean isAxDiagnosticsEnabled();
    void setAxDiagnosticsEnabled(boolean enabled);
    String wakeWord();
    void setAxWakeWord(String wakeWord);
    boolean isAxReplySpeechEnabled();
    void setAxReplySpeechEnabled(boolean enabled);
    boolean chatThinkingEnabled();
    void setAxChatThinkingEnabled(boolean enabled);
    boolean interruptOnPlayerSpeech();
    void setAxInterruptOnPlayerSpeech(boolean enabled);
    void save();
}
