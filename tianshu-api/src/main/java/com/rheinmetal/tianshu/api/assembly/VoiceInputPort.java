package com.rheinmetal.tianshu.api.assembly;

/**
 * The kernel's handle on voice capture.
 *
 * <p>The platform owns the key binding and calls these five methods; which module is listening, and what
 * it does with the audio, is not the platform's business. A module that does not implement this simply
 * never registers it, and the key does nothing.</p>
 */
public interface VoiceInputPort {

    boolean canAcceptVoiceInput();

    void beginVoiceInput();

    void endVoiceInput();

    void commitVoiceInput();

    void cancelVoiceInput();
}
