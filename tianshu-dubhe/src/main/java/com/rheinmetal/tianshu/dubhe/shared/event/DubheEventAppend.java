package com.rheinmetal.tianshu.dubhe.shared.event;

import java.util.Objects;

/**
 * One entry ready to be appended to the event history.
 *
 * <p>Every entry carries one already rendered sentence, and this layer never composes, prefixes, joins or formats
 * that sentence: no kind tag, no occurrence count, no value range. Folding only changes the granularity of what is
 * appended.</p>
 *
 * <p>{@link Transient} is the window-only shape: it is appended now so the model sees it in this conversation, but
 * memory compaction and retrieval skip it (G38). That distinction must be a type, not a boolean a caller may forget.</p>
 *
 * <p>{@link #kind()} is provenance metadata for the sink owner (logs, diagnostics, ledger); it is not part of the
 * appended line.</p>
 */
public sealed interface DubheEventAppend permits DubheEventAppend.Single, DubheEventAppend.Aggregated,
        DubheEventAppend.Transient {

    String kind();

    /** The line to append, already worded by whoever produced the entry. */
    String text();

    /** True when the line is window-only and must be excluded from compaction and retrieval. */
    default boolean transientEntry() {
        return false;
    }

    /** One occurrence appended with the sentence its producer rendered. */
    record Single(String kind, String text) implements DubheEventAppend {
        public Single {
            Objects.requireNonNull(kind, "event kind is required");
            Objects.requireNonNull(text, "event text is required");
        }
    }

    /** Several occurrences of one kind folded into one entry that memory may still learn from. */
    record Aggregated(String kind, int count, String text) implements DubheEventAppend {
        public Aggregated {
            Objects.requireNonNull(kind, "event kind is required");
            Objects.requireNonNull(text, "aggregate text is required");
            if (count < 2) {
                throw new IllegalArgumentException("an aggregate entry needs at least two occurrences");
            }
        }
    }

    /** A window-only line: shown in this conversation, never carried into long-term memory. */
    record Transient(String kind, int count, String text) implements DubheEventAppend {
        public Transient {
            Objects.requireNonNull(kind, "event kind is required");
            Objects.requireNonNull(text, "transient text is required");
            if (count < 1) {
                throw new IllegalArgumentException("a transient entry needs at least one occurrence");
            }
        }

        @Override
        public boolean transientEntry() {
            return true;
        }
    }
}