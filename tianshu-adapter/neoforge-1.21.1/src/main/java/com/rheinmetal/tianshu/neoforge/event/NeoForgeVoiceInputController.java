package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.api.assembly.InputSignals;

import java.util.Objects;

/**
 * Turns the voice key's state into input signals.
 *
 * <p>The platform states what happened — the key went down, the key came up — and stops there. It does not
 * look up a service, does not ask whether anything can accept input, and does not know which module (if any)
 * is listening: a module that is not installed simply never subscribes.</p>
 */
final class NeoForgeVoiceInputController {

    private final Runnable recordPresenceInput;
    private boolean inputActive;

    NeoForgeVoiceInputController(Runnable recordPresenceInput) {
        this.recordPresenceInput = Objects.requireNonNull(recordPresenceInput, "recordPresenceInput");
    }

    void tick(boolean keyAvailable, boolean keyDown) {
        if (!keyAvailable) {
            reset();
            return;
        }
        if (keyDown && !inputActive) {
            inputActive = true;
            dispatch(InputSignals.PUSH_TO_TALK_DOWN);
        } else if (!keyDown && inputActive) {
            inputActive = false;
            dispatch(InputSignals.PUSH_TO_TALK_UP);
        }
    }

    void reset() {
        inputActive = false;
    }

    private void dispatch(String channel) {
        recordPresenceInput.run();
        InputSignals.publish(channel, "");
    }
}