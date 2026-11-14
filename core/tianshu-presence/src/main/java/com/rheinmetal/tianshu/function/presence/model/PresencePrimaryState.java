package com.rheinmetal.tianshu.function.presence.model;

/**
 * What the player is experiencing right now; exactly one of these is the current state.
 *
 * <p>Listening is a primary state: the player is in a speech turn, the HUD says so and the icon pulses. It competes
 * for the single primary slot like every other state here. Background work is the only thing that stays parallel,
 * because a task can run next to whatever the player is doing without becoming what they are doing.</p>
 */
public enum PresencePrimaryState {
    IDLE,
    LISTENING,
    LOADING,
    THINKING,
    RESPONDING,
    RECONNECTING,
    ERROR
}
