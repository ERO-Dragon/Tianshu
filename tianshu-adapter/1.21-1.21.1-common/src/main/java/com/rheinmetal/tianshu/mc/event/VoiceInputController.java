package com.rheinmetal.tianshu.mc.event;

import com.rheinmetal.tianshu.api.assembly.InputSignals;

/**
 * Turns the voice key's state into input signals.
 *
 * <p>The platform states what happened — the key went down, the key came up — and stops there. It does not
 * look up a service, does not ask whether a module can accept input, and does not know which module (if any)
 * is listening: a module that is not installed simply never subscribes.</p>
 *
 * <p>{@code inputAvailable} is the session level gate, not a module level one: while the session is still starting
 * (or a module reports it is loading), the key must not open the microphone. A press that was already active when
 * the gate closed is released explicitly, and the key has to come up once before it can start a new capture —
 * otherwise reopening the gate while the player is still holding the key would open the microphone without a new
 * press.</p>
 */
public final class VoiceInputController {
    private boolean inputActive;
    private boolean releaseRequired;

    public void tick(boolean keyAvailable, boolean keyDown, boolean inputAvailable) {
        if (!inputAvailable) {
            if (inputActive) {
                inputActive = false;
                releaseRequired = true;
                InputSignals.publish(InputSignals.PUSH_TO_TALK_UP, "");
            }
            if (!keyDown) {
                // The player already let go while the gate was closed: the latch has done its job.
                releaseRequired = false;
            }
            return;
        }
        if (!keyAvailable) {
            reset();
            return;
        }
        if (!keyDown) {
            releaseRequired = false;
            if (inputActive) {
                inputActive = false;
                InputSignals.publish(InputSignals.PUSH_TO_TALK_UP, "");
            }
            return;
        }
        if (!inputActive && !releaseRequired) {
            inputActive = true;
            InputSignals.publish(InputSignals.PUSH_TO_TALK_DOWN, "");
        }
    }

    public void reset() {
        inputActive = false;
        releaseRequired = false;
    }
}