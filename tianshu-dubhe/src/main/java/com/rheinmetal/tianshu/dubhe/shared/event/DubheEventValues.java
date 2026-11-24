package com.rheinmetal.tianshu.dubhe.shared.event;

import java.util.Locale;

/**
 * The tokens a producer can only state as a value, and the language keys that word them.
 *
 * <p>Some facts carry a token rather than text: a weather state ("rain"), a dimension id
 * ("minecraft:overworld"). Putting that token straight into a sentence is how a translated line ends up
 * saying "weather changed: rain" — the token is the protocol's vocabulary, not language. This class is the
 * one place that turns such a token into a {@link DubheEventText.Arg.Key}, so every producer words them the
 * same way and a new dimension only needs a language entry.</p>
 *
 * <p>The raw token travels as the key's fallback: a modded dimension with no language entry then reads as
 * its id — raw, but true — instead of leaving a hole in the sentence.</p>
 */
public final class DubheEventValues {

    /** Prefix of every event value key; the shape and the token follow. */
    public static final String VALUE_PREFIX = "tianshu.dubhe.shared.event.value.";

    private static final String WEATHER_SEGMENT = "weather.";
    private static final String DIMENSION_SEGMENT = "dimension.";

    private DubheEventValues() {
    }

    /**
     * A weather state as a language reference.
     *
     * @param token the state the platform reported — {@code rain} / {@code thunder} / {@code clear}
     */
    public static DubheEventText.Arg weather(String token) {
        String value = normalizeToken(token);
        if (value.isEmpty()) {
            return DubheEventText.Arg.value("");
        }
        return DubheEventText.Arg.key(VALUE_PREFIX + WEATHER_SEGMENT + value, token.trim());
    }

    /**
     * A dimension as a language reference.
     *
     * <p>An id is namespaced with {@code :} and may carry a {@code /} path; both become {@code .} so the result is a
     * legal language key ({@code minecraft:overworld} → {@code ...dimension.minecraft.overworld}). A value that is
     * not namespaced is already a display name the client resolved, so it stays a literal: there is nothing for the
     * language table to add, and turning it into a key would only produce a lookup that cannot hit.</p>
     */
    public static DubheEventText.Arg dimension(String dimensionId) {
        String id = dimensionId == null ? "" : dimensionId.trim();
        if (id.isEmpty()) {
            return DubheEventText.Arg.value("");
        }
        if (id.indexOf(':') < 0) {
            return DubheEventText.Arg.value(id);
        }
        StringBuilder segment = new StringBuilder(id.length());
        for (int index = 0; index < id.length(); index++) {
            char ch = id.charAt(index);
            segment.append(ch == ':' || ch == '/' ? '.' : Character.toLowerCase(ch));
        }
        return DubheEventText.Arg.key(VALUE_PREFIX + DIMENSION_SEGMENT + segment, id);
    }

    /** The token lower-cased, or empty; a spell-out of {@link String#trim()} plus the locale rule. */
    private static String normalizeToken(String token) {
        return token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
    }
}