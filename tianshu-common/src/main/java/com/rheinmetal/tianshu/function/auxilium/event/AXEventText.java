package com.rheinmetal.tianshu.function.auxilium.event;

import java.util.List;
import java.util.Objects;

/**
 * Player facing sentence that still has to be localized.
 *
 * <p>The event layer never renders text by itself: it hands out a language key plus ordered
 * arguments, and whoever owns the language files resolves it. That keeps the decision layer free of
 * natural language, which is also why every event sentence lives in the client language files
 * instead of in a Java string constant.
 *
 * @param key  language key, for example {@code tianshu.ax.event.speech.tianshu.death}
 * @param args ordered arguments for the {@code %s} placeholders of that key; extra arguments are
 *             ignored by the resolver, so a template may use fewer than it receives
 */
public record AXEventText(String key, List<String> args) {
    /** Root of every event language key. */
    public static final String LANG_ROOT = "tianshu.ax.event";

    private static final String SPEECH_SEGMENT = "speech";
    private static final String AGGREGATE_SEGMENT = "aggregate";

    public AXEventText {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("event text key is required");
        }
        key = key.trim();
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** Sentence for a single event that is worth speaking. */
    public static AXEventText speech(String kind, String text) {
        return new AXEventText(key(SPEECH_SEGMENT, kind), List.of(text == null ? "" : text));
    }

    /** Sentence for one folded aggregate entry. */
    public static AXEventText aggregate(String kind, List<String> args) {
        return new AXEventText(key(AGGREGATE_SEGMENT, kind), args);
    }

    /** Builds the language key of a namespaced kind, {@code tianshu:death} to {@code tianshu.death}. */
    private static String kindKey(String kind) {
        Objects.requireNonNull(kind, "event kind is required");
        return kind.trim().replace(':', '.');
    }

    private static String key(String segment, String kind) {
        return LANG_ROOT + "." + segment + "." + kindKey(kind);
    }
}
