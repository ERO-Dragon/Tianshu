package com.rheinmetal.tianshu.function.asr.audio;

@FunctionalInterface
public interface AsrSpeechActivityListener {
    AsrSpeechActivityListener NOOP = (speaking, sessionId, occurredAtMillis) -> {
    };

    void onSpeechActivity(boolean speaking, long sessionId, long occurredAtMillis);

    static AsrSpeechActivityListener noop() {
        return NOOP;
    }
}
