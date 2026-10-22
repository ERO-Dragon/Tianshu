package com.rheinmetal.tianshu.function.auxilium;

public interface AXAssistantSettings {
    String DEFAULT_WAKE_WORD = "";
    double DEFAULT_FALLBACK_SCORE = 0.4D;

    AXAssistantSettings DEFAULT = () -> DEFAULT_WAKE_WORD;

    String wakeWord();

    default double fallbackScore() {
        return DEFAULT_FALLBACK_SCORE;
    }

    default boolean assistantEnabled() {
        return true;
    }

    default boolean chatThinkingEnabled() {
        return false;
    }

    default boolean allowInterruption() {
        return true;
    }
}
