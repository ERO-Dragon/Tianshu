package com.rheinmetal.tianshu.dubhe.shared.event;

/**
 * How one event is handled once the policy table has decided.
 *
 * <p>Five outcomes. The first three are spoken or historical; {@link #FOLD} exists so repeated context-only noise
 * stops polluting long-term memory; {@link #DROP} leaves nothing at all.</p>
 *
 * <ul>
 *   <li>{@link #IMMEDIATE} — try to speak on arrival, subject to interruption settings.</li>
 *   <li>{@link #DEFERRED} — hold for the next lull and say it then.</li>
 *   <li>{@link #SILENT} — never speak; append to history.</li>
 *   <li>{@link #FOLD} — never speak; one folded line may enter the current window, but it is transient: memory
 *       compaction skips it.</li>
 *   <li>{@link #DROP} — nothing: no speech, no history.</li>
 * </ul>
 */
public enum DubheEventClass {
    IMMEDIATE,
    DEFERRED,
    SILENT,
    FOLD,
    DROP;

    /** True when the event leaves a trace in the current window. {@link #DROP} is the only class that does not. */
    public boolean appends() {
        return this != DROP;
    }

    /** True when the event may reach the voice channel, now or during the next lull. */
    public boolean speaks() {
        return this == IMMEDIATE || this == DEFERRED;
    }

    /** True when the trace is window-only: appended now, excluded from compaction and retrieval. */
    public boolean transientContext() {
        return this == FOLD;
    }
}