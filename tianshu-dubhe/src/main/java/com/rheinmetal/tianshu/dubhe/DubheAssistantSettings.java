package com.rheinmetal.tianshu.dubhe;

import com.rheinmetal.tianshu.api.tool.ToolAutonomy;

public interface DubheAssistantSettings {
    String DEFAULT_WAKE_WORD = "";

    DubheAssistantSettings DEFAULT = () -> DEFAULT_WAKE_WORD;

    String wakeWord();

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

    /**
     * The tier this module allows itself (DEC-1): it is passed into every {@code ToolCaller} this module builds,
     * and it can only narrow what 舟楫 offers, never widen it.
     *
     * <p>Defaults to {@link ToolAutonomy#READ_ONLY}: a companion that has not been told it may act should not.
     */
    default ToolAutonomy autonomy() {
        return ToolAutonomy.READ_ONLY;
    }

    /**
     * 每档首次使用的授权记录；{@code null} 表示这个实现没有持久化（例如测试替身），此时不拦。
     */
    default com.rheinmetal.tianshu.dubhe.settings.DubheAutonomyConsent autonomyConsent() {
        return null;
    }
}
