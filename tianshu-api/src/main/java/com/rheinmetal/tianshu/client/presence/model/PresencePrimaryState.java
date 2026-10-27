package com.rheinmetal.tianshu.client.presence.model;

/**
 * What the player is experiencing right now; exactly one of these is the current state.
 *
 * <p>Background work is not here on purpose: a task runs in parallel with whatever the player is doing, so it is
 * reported as a parallel marker on the snapshot, not as a competing primary state. Listening is parallel for the
 * same reason.</p>
 */
public enum PresencePrimaryState {
    IDLE,
    LOADING,
    THINKING,
    RESPONDING,
    RECONNECTING,
    ERROR
}