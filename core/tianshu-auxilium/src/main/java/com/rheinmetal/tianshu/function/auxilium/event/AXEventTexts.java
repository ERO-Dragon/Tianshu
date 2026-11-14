package com.rheinmetal.tianshu.function.auxilium.event;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * The wording conventions shared by the event layer: fold templates, the kind key segment, and what a
 * rendered sentence is allowed to look like once it leaves a language table.
 *
 * <p>These used to live in the producer's own sentence class, which is exactly the split this refactor
 * removes: the producer states the semantics, and the layer that renders owns the key shape and the
 * sanitation of the result.
 */
public final class AXEventTexts {
    /** Fold template prefix; the kind segment follows, see {@link #kindSegment(String)}. */
    public static final String AGGREGATE_PREFIX = "tianshu.ax.event.aggregate.";

    /** Prefix of the language key that words one event line; the kind segment follows the same rule. */
    public static final String LINE_PREFIX = "tianshu.ax.event.line.";

    /**
     * Upper bound for a rendered sentence.
     *
     * <p>A rendered line reaches the history and the model prompt, so one entry has to stay bounded; the
     * cap is generous enough for a sentence and its arguments.
     */
    public static final int MAX_TEXT_CHARS = 120;

    private static final char NAMESPACE_SEPARATOR = ':';
    private static final char KEY_SEPARATOR = '.';

    private AXEventTexts() {
    }

    /**
     * The folded sentence of one window, as a template the renderer resolves.
     *
     * <p>A {@link AXEventFold#COUNT} window takes the count alone; a {@link AXEventFold#RANGE} window adds
     * the first and last magnitude, which are what make a folded health or hunger window readable at all.
     */
    public static AXEventText aggregate(String kind, AXEventFold fold, int count, Integer first, Integer last) {
        String key = AGGREGATE_PREFIX + kindSegment(kind);
        if (fold == AXEventFold.RANGE) {
            List<AXEventText.Arg> args = new ArrayList<>(3);
            args.add(AXEventText.Arg.value(Integer.toString(count)));
            args.add(AXEventText.Arg.value(valueOf(first)));
            args.add(AXEventText.Arg.value(valueOf(last)));
            return AXEventText.of(key, args);
        }
        return AXEventText.of(key, Integer.toString(count));
    }

    /** {@code tianshu:health_change} becomes the {@code tianshu.health_change} key segment. */
    public static String kindSegment(String namespacedKind) {
        if (namespacedKind == null) {
            return "";
        }
        return namespacedKind.trim().replace(NAMESPACE_SEPARATOR, KEY_SEPARATOR).replace('/', KEY_SEPARATOR);
    }

    /**
     * Treats a rendered sentence as untrusted output: NFC normalized, stripped of angle brackets (a
     * template must not be able to inject {@code <event>} or any other prompt tag through a line) and
     * truncated on a word boundary so one history line stays bounded.
     *
     * @return the cleaned sentence, possibly empty when the renderer produced nothing usable
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).trim();
        StringBuilder stripped = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch != '<' && ch != '>') {
                stripped.append(ch);
            }
        }
        return truncate(stripped.toString().trim());
    }

    private static String valueOf(Integer value) {
        return value == null ? "" : value.toString();
    }

    /**
     * Truncates on the last separator before the cap so a word or clause is not cut in half, and never
     * splits a surrogate pair.
     */
    private static String truncate(String value) {
        if (value.length() <= MAX_TEXT_CHARS) {
            return value;
        }
        int cut = MAX_TEXT_CHARS;
        if (Character.isLowSurrogate(value.charAt(cut))) {
            cut--;
        }
        for (int i = cut; i > MAX_TEXT_CHARS / 2; i--) {
            if (isSeparator(value.charAt(i - 1))) {
                return value.substring(0, i).trim();
            }
        }
        return value.substring(0, cut).trim();
    }

    private static boolean isSeparator(char ch) {
        return Character.isWhitespace(ch) || "，。、；：！？,.!?;:".indexOf(ch) >= 0;
    }
}
