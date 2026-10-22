package com.rheinmetal.tianshu.client.speech;

/**
 * Where an out-of-band sentence comes from.
 *
 * <p>Only lines that are produced <em>outside</em> a dialogue turn travel through
 * {@link AXSpeechOutlet}: a delegation receipt or conclusion, and a game event sentence. The dialogue's
 * own streaming speech keeps its own path, because it is streamed sentence by sentence inside a turn.
 */
public enum AXSpeechSource {
    DELEGATE,
    EVENT
}
