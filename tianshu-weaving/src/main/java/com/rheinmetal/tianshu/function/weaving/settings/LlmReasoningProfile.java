package com.rheinmetal.tianshu.function.weaving.settings;

import java.util.List;
import java.util.Objects;

/**
 * The ordered reasoning ladder an endpoint actually offers.
 *
 * <p>A tier is an index, not a vendor word: {@code tier 0} is the non-thinking slot when the
 * endpoint has one, and every following tier is one of the provider's own level names in ascending
 * order. No alias folding happens anywhere — if the provider later adds a level, it becomes another
 * tier. {@link LlmReasoningWire} only says which request field carries the selected name.</p>
 *
 * <p>A profile with no levels means the endpoint has no reasoning control; a caller that still
 * passes a tier gets the endpoint default and can read the capability from the runtime snapshot.</p>
 */
public record LlmReasoningProfile(
        LlmReasoningWire wire,
        boolean includesNoThinking,
        List<String> levels,
        int defaultTier
) {
    public static final LlmReasoningProfile NONE = new LlmReasoningProfile(
            LlmReasoningWire.NONE, false, List.of(), 0
    );

    public LlmReasoningProfile {
        wire = wire == null ? LlmReasoningWire.NONE : wire;
        levels = levels == null ? List.of() : levels.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(level -> !level.isEmpty())
                .toList();
        if (wire == LlmReasoningWire.NONE) {
            levels = List.of();
            includesNoThinking = false;
        }
        int count = (includesNoThinking ? 1 : 0) + levels.size();
        defaultTier = count == 0 ? 0 : Math.max(0, Math.min(defaultTier, count - 1));
    }

    /** Total selectable tiers, including the non-thinking slot when present. */
    public int tierCount() {
        return (includesNoThinking ? 1 : 0) + levels.size();
    }

    public boolean hasControl() {
        return tierCount() > 0;
    }

    /** Clamps a requested tier into this ladder; callers keep a valid number even across model switches. */
    public int clampTier(int tier) {
        if (tierCount() == 0) {
            return 0;
        }
        return Math.max(0, Math.min(tier, tierCount() - 1));
    }

    /**
     * The provider name for a tier, or {@code null} for the non-thinking slot. The caller must pass a
     * tier inside the ladder; use {@link #clampTier(int)} first when the value came from elsewhere.
     */
    public String levelName(int tier) {
        if (tierCount() == 0) {
            return null;
        }
        int applied = clampTier(tier);
        if (includesNoThinking) {
            return applied == 0 ? null : levels.get(applied - 1);
        }
        return levels.get(applied);
    }
}
