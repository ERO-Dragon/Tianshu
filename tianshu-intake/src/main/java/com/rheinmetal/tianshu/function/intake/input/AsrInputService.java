package com.rheinmetal.tianshu.function.intake.input;

/**
 * What this module does with a push-to-talk gesture.
 *
 * <p>It used to extend the kernel's voice port, which made the platform look this service up by interface and
 * call it. The gesture now arrives as an input signal instead, so the contract is this module's own: the
 * kernel does not name it, and the platform never finds it.</p>
 */
public interface AsrInputService {

    boolean canAcceptVoiceInput();

    void beginVoiceInput();

    void endVoiceInput();

    /**
     * Abandons the open turn without producing text.
     *
     * <p>Not a platform gesture: the host calls it while releasing input resources, so a turn that is still
     * recording cannot outlive the module's microphone ownership.</p>
     */
    void cancelVoiceInput();
}
