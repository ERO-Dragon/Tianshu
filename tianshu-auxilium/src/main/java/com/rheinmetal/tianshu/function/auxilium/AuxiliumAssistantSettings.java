package com.rheinmetal.tianshu.function.auxilium;

public interface AuxiliumAssistantSettings {
    String DEFAULT_WAKE_WORD = "";
    double DEFAULT_FALLBACK_SCORE = 0.4D;

    AuxiliumAssistantSettings DEFAULT = () -> DEFAULT_WAKE_WORD;

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

    /**
     * Whether the player may talk to the assistant by typing.
     *
     * <p>Subscribing to the text-input signal <em>is</em> the switch: the platform asks the bus whether
     * anything is listening before it opens the input bar, so disabling this makes the key do nothing rather
     * than opening a bar whose lines go nowhere.</p>
     */
    default boolean textInputEnabled() {
        return true;
    }
}
