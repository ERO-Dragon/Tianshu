package com.rheinmetal.tianshu.client.speech;

/**
 * The voice boundary: hand one sentence to whoever can actually speak it.
 *
 * <p>This interface holds no Minecraft API and no wording: an implementation resolves a keyed line
 * through the client language table and submits the sentence on the existing TTS capability. The shared
 * layer therefore keeps producing keys and literal texts and stays silent about how they are spoken.</p>
 *
 * <p>Contract for implementations — the callers rely on it:</p>
 * <ul>
 *   <li><b>never throw</b>: a missing or disabled voice channel is a diagnostic, not an error on the
 *       delegation or event path;</li>
 *   <li><b>never block</b>: submitting is a queue operation, not playback;</li>
 *   <li><b>preserve arrival order</b>: lines reach the voice in the order they are handed over; queuing
 *       and interrupting are the TTS layer's decisions, not this layer's.</li>
 * </ul>
 */
@FunctionalInterface
public interface AXSpeechOutlet {

    /** Outlet used when a host has no voice wiring: every line is dropped. */
    AXSpeechOutlet NONE = line -> {
    };

    void speak(AXSpeechLine line);
}
