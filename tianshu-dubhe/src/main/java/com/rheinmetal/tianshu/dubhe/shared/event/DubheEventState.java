package com.rheinmetal.tianshu.dubhe.shared.event;

/**
 * Where a kind sits on the attention list.
 *
 * <p>Three states, independent of the tier: the tier says how often a kind happens, the state says how the player
 * wants that kind treated. A kind with no declaration is {@link #NORMAL}.
 *
 * <p>{@link #EMPHASIS} and {@link #WEAKENED} are matrix columns, not permissions: (frequency row, state) picks one
 * handling class. A weakened frequent event is dropped; a weakened rare one still reaches the history.
 */
public enum DubheEventState {
    /** Emphasis: the player wants to hear this kind more (the old whitelist). */
    EMPHASIS,
    /** Normal: the documented baseline (the old neutral). */
    NORMAL,
    /** Weakened: the player asked to hear this kind less (the old blacklist). */
    WEAKENED;

    /** Parses a list id such as {@code "whitelist"}; an unknown or blank id is {@link #NORMAL}. */
    public static DubheEventState fromId(String id) {
        if (id == null || id.isBlank()) {
            return NORMAL;
        }
        String normalized = id.trim();
        for (DubheEventState value : values()) {
            if (value.name().equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        // Old table ids, so a hand-edited configuration keeps loading after the rename.
        return switch (normalized.toLowerCase(java.util.Locale.ROOT)) {
            case "whitelist" -> EMPHASIS;
            case "blacklist" -> WEAKENED;
            default -> NORMAL;
        };
    }

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
