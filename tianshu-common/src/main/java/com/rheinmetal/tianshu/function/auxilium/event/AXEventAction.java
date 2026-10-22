package com.rheinmetal.tianshu.function.auxilium.event;

/**
 * What the caller must do with one event right now.
 *
 * <p>This is the operation matrix of the event design document collapsed into one value: the tier
 * picks the column, the blacklist flag picks the row, and the speech gate turns "speak at once" into
 * "speak later" when a cooldown is still open.
 */
public enum AXEventAction {
    /** Append now and speak immediately: high tier, not blacklisted, speech gate granted. */
    APPEND_AND_SPEAK,
    /** Append (possibly folded into an aggregate window) and let the speaker queue the sentence. */
    APPEND_AND_BUFFER,
    /** Append only, never speak: blacklisted, or a low tier that is not blacklisted. */
    APPEND_ONLY,
    /** Discard at the entry, not even a history line: low tier plus a blacklist flag. */
    DROP;

    public boolean appends() {
        return this != DROP;
    }
}
