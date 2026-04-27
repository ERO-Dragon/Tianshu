package com.rheinmetal.tianshu.function.AcousticRadar;

public interface AlertSpeaker {
    void speakAlert(String text);

    void speakAlertWithInterrupt(String text);
}
