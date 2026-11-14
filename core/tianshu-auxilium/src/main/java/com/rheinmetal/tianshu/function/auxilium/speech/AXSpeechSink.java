package com.rheinmetal.tianshu.function.auxilium.speech;

/**
 * Where this module's spoken output goes.
 *
 * <p>Deliberately free of speech-module types: auxilium integrates with that module rather than requiring
 * it, so the only implementation that knows its payloads lives in the bridge package and is loaded only
 * when it is installed. {@link #NONE} is what the module wires otherwise, which keeps every call site valid
 * and simply produces no sound.</p>
 */
public interface AXSpeechSink {

    void speak(AXSpeechUtterance utterance);

    /** Stops everything this module already submitted to the speech channel. */
    void stopSource(String reasonCode);

    AXSpeechSink NONE = new AXSpeechSink() {
        @Override
        public void speak(AXSpeechUtterance utterance) {
        }

        @Override
        public void stopSource(String reasonCode) {
        }
    };
}