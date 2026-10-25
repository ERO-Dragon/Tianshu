package com.rheinmetal.tianshu.function.intake.input;

public interface AsrInputService extends com.rheinmetal.tianshu.api.assembly.VoiceInputPort {
    boolean canAcceptVoiceInput();

    void beginVoiceInput();

    void endVoiceInput();

    void commitVoiceInput();

    void cancelVoiceInput();
}
