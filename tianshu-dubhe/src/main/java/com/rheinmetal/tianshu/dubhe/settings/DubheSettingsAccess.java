package com.rheinmetal.tianshu.dubhe.settings;

import com.rheinmetal.tianshu.api.tool.ToolAutonomy;

public interface DubheSettingsAccess {
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

    void setAxAutonomy(ToolAutonomy autonomy);
    void save();
}
