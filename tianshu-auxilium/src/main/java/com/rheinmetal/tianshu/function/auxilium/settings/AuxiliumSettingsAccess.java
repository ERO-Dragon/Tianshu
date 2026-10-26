package com.rheinmetal.tianshu.function.auxilium.settings;

public interface AuxiliumSettingsAccess {
    boolean assistantEnabled();
    void setAxEnabled(boolean enabled);
    String wakeWord();
    void setAxWakeWord(String wakeWord);
    boolean isAxReplySpeechEnabled();
    void setAxReplySpeechEnabled(boolean enabled);
    boolean chatThinkingEnabled();
    void setAxChatThinkingEnabled(boolean enabled);
    boolean allowInterruption();
    void setAxAllowInterruption(boolean enabled);
    boolean textInputEnabled();
    void setAxTextInputEnabled(boolean enabled);
    void save();
}
