package com.rheinmetal.tianshu.function.intake.state;

/**
 * What the push-to-talk controller is doing with the microphone right now.
 *
 * <p>There is no error or stopping state: a failed recognition returns to {@link #IDLE} and reports the
 * failure through module status, and module stop resets the machine instead of parking it in a state no key
 * press can leave.</p>
 */
public enum AsrState {
    IDLE,
    CAPTURING,
    RECOGNIZING
}
