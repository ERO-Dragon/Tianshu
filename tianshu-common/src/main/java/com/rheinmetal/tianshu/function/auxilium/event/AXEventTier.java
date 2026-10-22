package com.rheinmetal.tianshu.function.auxilium.event;

import java.util.Locale;

/**
 * Importance tier of an event kind.
 *
 * <p>A tier is a static property of the event <em>kind</em>, never of a single occurrence: the same
 * kind always resolves to the same tier, so cooldowns, aggregation and rendered history bytes stay
 * reproducible across replays.
 */
public enum AXEventTier {
    /** Immediate threat or irreversible turning point: speak at once unless gated. */
    HIGH("high"),
    /** Clear in-game gain but not urgent: append now, speak when the player is idle. */
    MID("mid"),
    /** Pure state fluctuation or something already visible on screen: append only, never speak. */
    LOW("low");

    private final String id;

    AXEventTier(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /**
     * Parses a tier id such as {@code "high"}.
     *
     * @return the matching tier, or {@code null} when the id is blank or unknown so the caller can
     *         apply its own fallback instead of silently picking a tier here
     */
    public static AXEventTier fromId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (AXEventTier tier : values()) {
            if (tier.id.equals(normalized)) {
                return tier;
            }
        }
        return null;
    }
}
