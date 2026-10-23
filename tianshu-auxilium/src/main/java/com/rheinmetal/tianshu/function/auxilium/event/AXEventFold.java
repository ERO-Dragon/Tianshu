package com.rheinmetal.tianshu.function.auxilium.event;

/**
 * How a folded window is worded.
 *
 * <p>The shape is part of the event's semantics, so the receiving side holds it instead of the producer
 * handing over a renderer: a count-only fold and a range fold are two different sentences, and the layer
 * that owns the window is the only one that knows the count and the first/last magnitudes. Replacing the
 * old producer callback with this enum is what lets the folded sentence be rendered from the same language
 * table as the single one.
 */
public enum AXEventFold {
    /** The occurrences stay separate entries; the kind is a moment, not a rate. */
    NONE,
    /** The folded sentence takes the occurrence count only. */
    COUNT,
    /** The folded sentence takes the count plus the first and last numeric metric. */
    RANGE
}
