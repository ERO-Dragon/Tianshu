package com.rheinmetal.tianshu.function.auxilium.event;

/**
 * Renders the one sentence that replaces a folded window of occurrences.
 *
 * <p>The renderer is implemented by the producer and handed over with every event, so this layer
 * never owns a template, a language key, a number format or a joining character. It reports only how
 * many occurrences were folded and which numeric metric the first and the last one carried; the
 * producer turns that into wording in its own language.
 *
 * <p>Returning {@code null} or a blank string is allowed and meaningful: the producer has no folded
 * sentence for this kind, so the layer appends the retained occurrences individually instead of
 * inventing a line of its own. A renderer that throws is treated the same way, so a broken producer
 * can never break the history pipeline.
 */
@FunctionalInterface
public interface AXEventAggregateRenderer {
    /**
     * @param count      folded occurrence count, always at least two
     * @param firstValue numeric metric of the first occurrence, {@code null} when it carried none
     * @param lastValue  numeric metric of the last occurrence, {@code null} when it carried none
     * @return the folded sentence, or {@code null}/blank when this producer cannot fold the window
     */
    String render(int count, Integer firstValue, Integer lastValue);
}
