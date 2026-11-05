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

    // --- persistence ------------------------------------------------------------------------------
    /*
     * A ladder is stored as one escaped field, the same way a credential is: wire;noThinking;levels;default
     * with levels comma separated. Level names are provider words (letters, digits, '-' and '_'), so the
     * two separators cannot appear inside one. Anything unreadable decodes to null, which the caller
     * reads as "no explicit ladder" — a hand-edited file may never crash the settings page.
     */

    private static final char FIELD = ';';
    private static final char LEVEL = ',';
    private static final int FIELD_COUNT = 4;

    public String encode() {
        StringBuilder builder = new StringBuilder();
        builder.append(wire.name()).append(FIELD)
                .append(includesNoThinking ? '1' : '0').append(FIELD)
                .append(String.join(String.valueOf(LEVEL), levels)).append(FIELD)
                .append(defaultTier);
        return builder.toString();
    }

    /** @return the decoded ladder, or {@code null} when the text is not one this build understands */
    public static LlmReasoningProfile decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] fields = encoded.split(String.valueOf(FIELD), -1);
        if (fields.length != FIELD_COUNT) {
            return null;
        }
        LlmReasoningWire wire = parseWire(fields[0]);
        if (wire == null) {
            return null;
        }
        Integer defaultTier = parseTier(fields[3]);
        if (defaultTier == null) {
            return null;
        }
        List<String> levels = fields[2].isBlank()
                ? List.of()
                : List.of(fields[2].split(String.valueOf(LEVEL), -1));
        return new LlmReasoningProfile(wire, "1".equals(fields[1].trim()), levels, defaultTier);
    }

    private static LlmReasoningWire parseWire(String raw) {
        String normalized = raw == null ? "" : raw.trim();
        for (LlmReasoningWire candidate : LlmReasoningWire.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    private static Integer parseTier(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException unreadableTier) {
            return null;
        }
    }
}
