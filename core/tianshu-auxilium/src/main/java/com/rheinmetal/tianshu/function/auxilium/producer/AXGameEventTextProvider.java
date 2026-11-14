package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.function.auxilium.event.AXEventText;

import java.util.ArrayList;
import java.util.List;

/**
 * The one door to a language table, and now to rendering an event's wording.
 *
 * <p>{@link #exists(String)} and {@link #text(String, Object...)} are the raw primitives; they live on the
 * platform side, where the client language files are addressed, and the common modules never look at a
 * language table directly.
 *
 * <p>{@link #render(AXEventText)} is the semantic entry the event layer uses: the producer states a template
 * key plus arguments, and this method walks that structure, resolves nested fragments depth first and
 * substitutes the results. It exists so that "kind plus arguments to sentence" is expressed once here
 * instead of by every producer.
 *
 * <p>A key that is missing resolves to an empty string instead of the key itself: an unresolved key is
 * never allowed to leak into the history, and an empty sentence means "cannot word it", which is the
 * caller's signal to fall back to the kind code or to skip the event.
 */
public interface AXGameEventTextProvider {
    /** Provider used when a platform has not wired a language table yet: never renders anything. */
    AXGameEventTextProvider NONE = new AXGameEventTextProvider() {
        @Override
        public boolean exists(String key) {
            return false;
        }

        @Override
        public String text(String key, Object... args) {
            return "";
        }
    };

    boolean exists(String key);

    /** Resolved sentence, or an empty string when the key is unknown. */
    String text(String key, Object... args);

    /**
     * Renders one template and its arguments, or returns an empty string.
     *
     * <p>Nesting is capped at {@link AXEventText#MAX_REF_DEPTH}: a language table that references itself,
     * through a misconfigured template or a cycle, would otherwise recurse until the stack dies. Overrunning
     * the cap is treated exactly like a missing key — blank, so the caller falls back — and it is never
     * reported as a sentence, because there is nothing to report.
     */
    default String render(AXEventText text) {
        return render(text, 0);
    }

    private String render(AXEventText text, int depth) {
        if (text == null || text.blank() || depth > AXEventText.MAX_REF_DEPTH) {
            return "";
        }
        if (!exists(text.key())) {
            return "";
        }
        List<Object> values = new ArrayList<>(text.args().size());
        for (AXEventText.Arg arg : text.args()) {
            if (arg instanceof AXEventText.Arg.Value value) {
                values.add(value.text());
                continue;
            }
            if (arg instanceof AXEventText.Arg.Nested nested) {
                String fragment = render(nested.text(), depth + 1);
                if (fragment.isBlank()) {
                    // A fragment that cannot be worded cannot be substituted either: rendering the rest
                    // would put a hole in the sentence, which reads worse than falling back.
                    return "";
                }
                values.add(fragment);
            }
        }
        return text(text.key(), values.toArray());
    }
}
