package com.rheinmetal.tianshu.function.intake.input;

public interface AsrInputService {
    boolean canAcceptVoiceInput();

    void beginVoiceInput();

    void endVoiceInput();

    void commitVoiceInput();

    void cancelVoiceInput();
}
