package com.rheinmetal.tianshu.function.intake.protocol;

/**
 * The topics the speech-input side produces.
 *
 * <p>They live in this module, not in the kernel: a topic name is part of a producer's contract, and the
 * kernel keeps only its own three ({@code SYSTEM.RUNTIME_INTERRUPT}, {@code MODULE.STATUS},
 * {@code WORLD_EVENT}). Deleting this module therefore deletes its vocabulary with it.</p>
 */
public final class IntakeTopics {

    public static final String INPUT_ASR_SPEECH_ACTIVITY = "INPUT.ASR_SPEECH_ACTIVITY";
    public static final String INPUT_ASR_FINAL_TEXT = "INPUT.ASR_FINAL_TEXT";
    public static final String IR_RESULT = "IR.RESULT";

    private IntakeTopics() {
    }
}
