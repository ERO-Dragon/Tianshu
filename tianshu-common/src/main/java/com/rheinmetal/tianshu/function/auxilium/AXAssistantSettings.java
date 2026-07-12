package com.rheinmetal.tianshu.function.auxilium;

public interface AXAssistantSettings {
    String DEFAULT_WAKE_WORD = "天枢";

    AXAssistantSettings DEFAULT = () -> DEFAULT_WAKE_WORD;

    String wakeWord();

    default boolean assistantEnabled() {
        return true;
    }

    default boolean chatThinkingEnabled() {
        return false;
    }

    default boolean interruptOnPlayerSpeech() {
        return true;
    }
}
