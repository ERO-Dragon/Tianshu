package com.rheinmetal.tianshu.function.auxilium.event;

import java.util.Objects;

/**
 * One entry ready to be appended to the event history.
 *
 * <p>Every entry carries the line exactly as the producer rendered it, and this layer never composes,
 * prefixes, joins or formats that line: no kind tag, no occurrence count, no value range. Folding only
 * changes the granularity of what is appended, and a folded entry holds the sentence the producer's
 * {@link AXEventAggregateRenderer} returned.
 *
 * <p>{@link #kind()} is provenance metadata for the sink owner (logs, diagnostics, ledger); it is not
 * part of the appended line.
 */
public sealed interface AXEventAppend permits AXEventAppend.Single, AXEventAppend.Aggregated {
    String kind();

    /** The line to append, already worded by whoever produced the entry. */
    String text();

    /** One occurrence appended with the sentence its producer rendered. */
    record Single(String kind, String text) implements AXEventAppend {
        public Single {
            Objects.requireNonNull(kind, "event kind is required");
            Objects.requireNonNull(text, "event text is required");
        }
    }

    /**
     * Several occurrences of one kind folded into one entry.
     *
     * @param count folded occurrence count, at least two
     * @param text  the folded sentence, rendered by the producer's aggregate renderer
     */
    record Aggregated(String kind, int count, String text) implements AXEventAppend {
        public Aggregated {
            Objects.requireNonNull(kind, "event kind is required");
            Objects.requireNonNull(text, "aggregate text is required");
            if (count < 2) {
                throw new IllegalArgumentException("an aggregate entry needs at least two occurrences");
            }
        }
    }
}
