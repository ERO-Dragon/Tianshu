package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.function.auxilium.bridge.AXDialogueChannel;
import com.rheinmetal.tianshu.function.auxilium.input.AXDialogueInput;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;

import java.util.Objects;

/**
 * Tells the dialogue domain when a session is done with, or needs more time.
 *
 * <p>The command vocabulary belongs to that domain, so the two calls are expressed as intent — release, extend
 * — and the bridge turns them into the partner's payload. With no dialogue domain both calls are no-ops: there
 * is no session to release.</p>
 */
public final class AXSessionController {
    private final AXDialogueChannel channel;

    public AXSessionController(AXDialogueChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    public void release(TianshuEnvelope parent, AXDialogueInput delivery, DialogueReleaseReason reason) {
        if (parent == null || delivery == null || delivery.sessionId().isBlank()) {
            return;
        }
        channel.releaseSession(parent, delivery, reason);
    }

    public void extend(TianshuEnvelope parent, AXDialogueInput delivery, long requestedProcessingMillis) {
        if (parent == null || delivery == null || delivery.sessionId().isBlank() || requestedProcessingMillis <= 0L) {
            return;
        }
        channel.extendSession(parent, delivery, requestedProcessingMillis);
    }
}