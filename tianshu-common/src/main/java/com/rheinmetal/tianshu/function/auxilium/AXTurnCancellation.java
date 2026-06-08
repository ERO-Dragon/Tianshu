package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.ia.model.DialogueReleaseReason;

public record AXTurnCancellation(String message, DialogueReleaseReason releaseReason) {
    public AXTurnCancellation {
        message = message == null || message.isBlank() ? "AX turn cancelled" : message.trim();
        releaseReason = releaseReason == null ? DialogueReleaseReason.OWNER_FAILED : releaseReason;
    }

    public static AXTurnCancellation playerInterrupted(String message) {
        return new AXTurnCancellation(message, DialogueReleaseReason.PLAYER_CANCELLED);
    }

    public static AXTurnCancellation moduleUnloaded(String message) {
        return new AXTurnCancellation(message, DialogueReleaseReason.MODULE_UNLOADED);
    }

    public static AXTurnCancellation expired(String message) {
        return new AXTurnCancellation(message, DialogueReleaseReason.EXPIRED);
    }
}
