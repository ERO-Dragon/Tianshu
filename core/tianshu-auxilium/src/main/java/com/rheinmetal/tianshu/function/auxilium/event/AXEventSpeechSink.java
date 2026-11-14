package com.rheinmetal.tianshu.function.auxilium.event;

/**
 * The speech half of an event decision: the sentence the producer rendered, handed to the voice layer.
 *
 * <p>The layer holds no wording (see {@link AXGameEventService}), so this sink never receives a key it
 * would have to render: the text is already the producer's own sentence and is passed through unchanged.
 * An implementation forwards it to the platform speech outlet; the reference implementation only reports
 * it, which is what keeps a headless host silent without failing the event.</p>
 *
 * <p>Implementations must not throw and must not block: a voice channel that is missing or disabled is a
 * diagnostic, never an error on the event path.</p>
 */
@FunctionalInterface
public interface AXEventSpeechSink {

    /** Sink used when a host has not wired a voice channel: the sentence is dropped, nothing fails. */
    AXEventSpeechSink NOOP = (kind, text) -> {
    };

    /**
     * @param kind namespaced event kind the sentence belongs to
     * @param text the producer's sentence, already worded
     */
    void speak(String kind, String text);
}
