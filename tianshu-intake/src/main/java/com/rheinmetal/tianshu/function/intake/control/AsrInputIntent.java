package com.rheinmetal.tianshu.function.intake.control;

/**
 * The semantic actions the input channel can ask of the controller.
 *
 * <p>Push-to-talk has two gestures (down and up); cancel is the module-lifecycle action that abandons an open
 * turn while releasing the microphone. A separate commit action and an interrupt intent used to exist without
 * a producer — release already is the commit, and runtime interrupts arrive through the protocol instead.</p>
 */
public enum AsrInputIntent {
    BEGIN,
    END,
    CANCEL
}
