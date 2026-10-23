package com.rheinmetal.tianshu.function.auxilium.event;

/**
 * How one event is handled once the policy table has decided.
 *
 * <p>Four outcomes on one ladder, from "say it now" to "say nothing, ever":
 *
 * <ul>
 *   <li>{@link #IMMEDIATE} — try to speak on arrival. A busy assistant decides through
 *       {@code AXAssistantSettings.allowInterruption()} whether that means interrupting or waiting its turn.</li>
 *   <li>{@link #DEFERRED} — hold it in the deferred buffer and say it during the next lull, as one voluntary
 *       turn.</li>
 *   <li>{@link #SILENT} — never speak it; fold it into the history with a count so memory still learns it
 *       happened.</li>
 *   <li>{@link #DROP} — nothing at all: no speech, no history.</li>
 * </ul>
 *
 * <p>The ladder is what the attention list walks on: a whitelisted kind sits one step above its tier's baseline and a
 * blacklisted one a step below it. Frequency is <em>not</em> expressed here — repetition is a property of the tier itself
 * ({@link AXEventTier}), so a kind that keeps happening is corrected to a more frequent tier and its class is then read
 * off the policy table again.
 */
public enum AXEventClass {
    IMMEDIATE,
    DEFERRED,
    SILENT,
    DROP;

    /** True when the event leaves a trace in the history. {@link #DROP} is the only class that does not. */
    public boolean appends() {
        return this != DROP;
    }

    /** True when the event may reach the voice channel, now or during the next lull. */
    public boolean speaks() {
        return this == IMMEDIATE || this == DEFERRED;
    }

    /** One step down the ladder; {@link #DROP} stays where it is. */
    public AXEventClass downgraded() {
        return switch (this) {
            case IMMEDIATE -> DEFERRED;
            case DEFERRED -> SILENT;
            case SILENT, DROP -> DROP;
        };
    }

    /** One step up the ladder; {@link #IMMEDIATE} stays where it is. */
    public AXEventClass upgraded() {
        return switch (this) {
            case DROP -> SILENT;
            case SILENT -> DEFERRED;
            case DEFERRED, IMMEDIATE -> IMMEDIATE;
        };
    }
}
