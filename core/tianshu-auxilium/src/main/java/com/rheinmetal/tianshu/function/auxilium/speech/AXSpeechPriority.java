package com.rheinmetal.tianshu.function.auxilium.speech;

/**
 * How soon an out-of-band line should be heard.
 *
 * <p>Both values wait their turn: neither interrupts the sentence that is playing, because a delegation
 * result or an event sentence is never allowed to cut the assistant off. They differ only in where the
 * line joins the queue, which is also what the protocol priority carries.</p>
 *
 * <ul>
 *   <li>{@link #NORMAL}: after the current playback session (a receipt, appended behind whatever plays).</li>
 *   <li>{@link #HIGH}: as soon as the current sentence ends (a conclusion or an event the player is
 *       waiting for).</li>
 * </ul>
 */
public enum AXSpeechPriority {
    NORMAL,
    HIGH
}
