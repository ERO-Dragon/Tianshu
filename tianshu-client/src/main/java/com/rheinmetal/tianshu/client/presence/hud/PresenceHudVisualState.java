package com.rheinmetal.tianshu.client.presence.hud;

import com.rheinmetal.tianshu.client.presence.model.PresencePrimaryState;

/**
 * Product-level visual states. Protocol activity types remain separate from this presentation
 * mapping so HUD changes do not expand the cross-module activity contract.
 */
public enum PresenceHudVisualState {
    IDLE,
    LOADING,
    TASK,
    CHAT,
    CHAT_THINKING;

    public static PresenceHudVisualState from(PresencePrimaryState state) {
        return switch (state == null ? PresencePrimaryState.IDLE : state) {
            case LOADING -> LOADING;
            case THINKING -> CHAT_THINKING;
            case RESPONDING -> CHAT;
            // A failed capability has no animation of its own: the status text carries the meaning, and reusing the
            // task visual keeps the shader set unchanged. Reconnecting looks like loading for the same reason.
            case RECONNECTING -> LOADING;
            case ERROR -> TASK;
            case IDLE -> IDLE;
        };
    }
}