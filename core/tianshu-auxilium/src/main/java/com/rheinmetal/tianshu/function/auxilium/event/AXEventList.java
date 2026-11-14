package com.rheinmetal.tianshu.function.auxilium.event;

/**
 * Where a kind sits on the attention list.
 *
 * <p>Three states, and they are independent of the tier: the tier says how often a kind happens, the list says
 * whether the player wants to hear about it out of the ordinary. A kind not listed anywhere is {@link #NEUTRAL},
 * which is the baseline for its tier.
 *
 * <p>{@link #WHITELIST} and {@link #BLACKLIST} are one step up or down the
 * {@link AXEventClass} ladder from that baseline — see {@link AXEventPolicy}. They are not permissions on/off: a
 * whitelisted frequent event becomes silent-with-a-count, not a loud one, and a blacklisted rare event still reaches
 * the history.
 */
public enum AXEventList {
    WHITELIST,
    NEUTRAL,
    BLACKLIST;

    /** Parses a list id such as {@code "whitelist"}; an unknown or blank id is {@link #NEUTRAL}. */
    public static AXEventList fromId(String id) {
        if (id == null || id.isBlank()) {
            return NEUTRAL;
        }
        String normalized = id.trim();
        for (AXEventList value : values()) {
            if (value.name().equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        return NEUTRAL;
    }

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
