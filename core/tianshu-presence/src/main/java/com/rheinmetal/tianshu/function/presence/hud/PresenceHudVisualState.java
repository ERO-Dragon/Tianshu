package com.rheinmetal.tianshu.function.presence.hud;

import com.rheinmetal.tianshu.function.presence.model.PresencePrimaryState;

/**
 * Product-level visual states. Protocol activity types remain separate from this presentation mapping so HUD changes
 * do not expand the cross-module activity contract.
 *
 * <p>{@code LISTENING} sits on the same primary axis as the others: it is the idle visual with the listening pulse
 * turned on, not a second flag layered over another state.</p>
 */
public enum PresenceHudVisualState {
    IDLE,
    LOADING,
    TASK,
    CHAT,
    CHAT_THINKING,
    LISTENING;

    public static PresenceHudVisualState from(PresencePrimaryState state) {
        return switch (state == null ? PresencePrimaryState.IDLE : state) {
            case IDLE -> IDLE;
            case LISTENING -> LISTENING;
            case LOADING -> LOADING;
            case THINKING -> CHAT_THINKING;
            case RESPONDING -> CHAT;
            // A failed capability has no animation of its own: the status text carries the meaning, and reusing the
            // task visual keeps the shader set unchanged. Reconnecting looks like loading for the same reason.
            case RECONNECTING -> LOADING;
            case ERROR -> TASK;
        };
    }
}
