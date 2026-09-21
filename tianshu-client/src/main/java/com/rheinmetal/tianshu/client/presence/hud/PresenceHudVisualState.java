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
            case PROCESSING_TASK -> TASK;
            case THINKING -> CHAT_THINKING;
            case RESPONDING -> CHAT;
            case IDLE -> IDLE;
        };
    }
}
