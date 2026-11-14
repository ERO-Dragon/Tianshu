package com.rheinmetal.tianshu.function.auxilium.event;

import java.util.Locale;

/**
 * How often an event kind happens.
 *
 * <p>The tier answers one question only — <em>frequency</em> — and the policy table turns that into a
 * handling class. {@link #RARE} therefore means "almost never happens", which is why it is the tier that may
 * interrupt: a death, a first discovery, a dimension change. {@link #FREQUENT} means "happens constantly", the
 * tier that must stay quiet so it does not talk over itself.
 *
 * <p>The mapping from frequency to handling is deliberately not in this type: see {@link AXEventPolicy} for the
 * single table. A tier is a static property of a kind, never of one occurrence, so the same kind always
 * resolves the same way on a replay.
 */
public enum AXEventTier {
    /** Rare and therefore important: worth interrupting for, unless it is blacklisted. */
    RARE("RARE"),
    /** Ordinary: worth saying, but not worth interrupting for; waits for a lull. */
    NORMAL("NORMAL"),
    /** Constant background fluctuation: never spoken, folded into the history with a count. */
    FREQUENT("FREQUENT");

    private final String id;

    AXEventTier(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /**
     * One step towards "this happens all the time"; {@link #FREQUENT} is already the last tier.
     *
     * <p>The step belongs to the tier, not to the handling class: a kind that keeps happening is by definition a more
     * frequent kind than its table row says. What that means for the player is then read off the policy table, which
     * stays a pure function of tier and list.
     */
    public AXEventTier downgraded() {
        return switch (this) {
            case RARE -> NORMAL;
            case NORMAL, FREQUENT -> FREQUENT;
        };
    }

    /**
     * Parses a tier id such as {@code "RARE"} (case insensitive).
     *
     * @return the matching tier, or {@code null} when the id is blank or unknown so the caller can apply its own
     *         fallback instead of silently picking a tier here
     */
    public static AXEventTier fromId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalized = id.trim().toUpperCase(Locale.ROOT);
        for (AXEventTier tier : values()) {
            if (tier.id.equals(normalized)) {
                return tier;
            }
        }
        return null;
    }
}
