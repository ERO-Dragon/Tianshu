package com.rheinmetal.tianshu.function.auxilium.event;

import java.util.Objects;

/**
 * One entry ready to be appended to the event history.
 *
 * <p>Aggregation only changes the granularity of what is appended; it never drops a non blacklisted
 * event, so a folded window always ends as either one {@link Aggregated} entry or the original
 * {@link Single} entries.
 */
public sealed interface AXEventAppend permits AXEventAppend.Single, AXEventAppend.Aggregated {
    String kind();

    /**
     * One history line for this entry.
     *
     * <p>The line is deliberately language neutral: it is model facing history, not player facing
     * text. Player facing wording stays in the language keys behind the entry's sentence, and this
     * layer never renders natural language itself.
     */
    default String historyText() {
        return switch (this) {
            case Single single -> single.kind() + " " + single.text();
            case Aggregated aggregate -> aggregate.historyLine();
        };
    }

    /** A single occurrence appended with its own payload text. */
    record Single(String kind, String text) implements AXEventAppend {
        public Single {
            Objects.requireNonNull(kind, "event kind is required");
            Objects.requireNonNull(text, "event text is required");
        }
    }

    /**
     * Several occurrences of one kind folded into one entry.
     *
     * @param count folded occurrence count
     * @param first numeric metric of the first occurrence, {@code null} when the kind carries none
     * @param last  numeric metric of the last occurrence, {@code null} when the kind carries none
     * @param min   lowest numeric metric in the window, {@code null} when the kind carries none
     * @param max   highest numeric metric in the window, {@code null} when the kind carries none
     */
    record Aggregated(String kind, int count, Integer first, Integer last, Integer min, Integer max,
                      AXEventText sentence) implements AXEventAppend {
        public Aggregated {
            Objects.requireNonNull(kind, "event kind is required");
            Objects.requireNonNull(sentence, "aggregate sentence is required");
            if (count < 2) {
                throw new IllegalArgumentException("an aggregate entry needs at least two occurrences");
            }
        }

        private String historyLine() {
            String range = first == null || last == null ? "" : " (" + first + "->" + last + ")";
            return kind + " x" + count + range;
        }
    }
}
