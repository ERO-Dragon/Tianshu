package com.rheinmetal.tianshu.dubhe;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;

public record DubheTurnCancellation(String message, DialogueReleaseReason releaseReason) {
    public DubheTurnCancellation {
        message = message == null || message.isBlank() ? "AX turn cancelled" : message.trim();
        releaseReason = releaseReason == null ? DialogueReleaseReason.OWNER_FAILED : releaseReason;
    }

    public static DubheTurnCancellation playerInterrupted(String message) {
        return new DubheTurnCancellation(message, DialogueReleaseReason.PLAYER_CANCELLED);
    }

    public static DubheTurnCancellation moduleUnloaded(String message) {
        return new DubheTurnCancellation(message, DialogueReleaseReason.MODULE_UNLOADED);
    }

    public static DubheTurnCancellation expired(String message) {
        return new DubheTurnCancellation(message, DialogueReleaseReason.EXPIRED);
    }
}
