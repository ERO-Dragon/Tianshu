package com.rheinmetal.tianshu.function.auxilium.event;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One event submitted to the layer, exactly as a module hands it over.
 *
 * <p>The descriptor is a module agnostic contract: any module may submit any namespaced kind and the
 * layer treats every submission the same way. {@code sourceId} is provenance only - it is recorded
 * with the entry and it selects a cooldown slot and an aggregation window, and it never reaches a
 * decision branch.
 *
 * <p>{@code text} is the event's wording <em>as semantics</em>: a template key plus its arguments
 * ({@link AXEventText}). The producer never words a sentence; the layer renders it once through the
 * language table it was given, so every producer shares one rendering path instead of implementing one.
 * The rendered sentence is what reaches the history and the voice channel.
 *
 * <p>{@code fold} is how a closed aggregation window is worded ({@link AXEventFold}). It replaced a
 * producer supplied renderer callback: the window knows its count and its first and last magnitudes, the
 * fold says which of them the sentence takes, and the same renderer that words the single event words the
 * folded one. {@link AXEventFold#NONE} keeps occurrences separate, which is the shape for a one-off moment.
 *
 * <p>{@code tier} is optional: when it is {@code null} the dispatcher consults the built-in tier
 * table and falls back to {@link AXEventTier#LOW} when the kind is unknown. {@code suppress} forces
 * the blacklist flag, which only removes the speech permission and never removes the history entry
 * (the sole exception is {@code suppress} combined with an effective low tier, which drops the event
 * at the entry).
 */
public record AXEventDescriptor(
        String kind,
        AXEventTier tier,
        boolean suppress,
        AXEventPayload payload,
        AXEventText text,
        AXEventFold fold,
        String sourceId
) {
    /** Namespace plus local name, lowercase, colon separated; see kind discipline in the design doc. */
    private static final Pattern KIND_PATTERN = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** Source recorded when a producer does not name one. */
    public static final String UNKNOWN_SOURCE = "unknown";

    public AXEventDescriptor {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("event kind is required");
        }
        kind = kind.trim();
        if (!KIND_PATTERN.matcher(kind).matches()) {
            throw new IllegalArgumentException("event kind must be namespaced, for example tianshu:death: " + kind);
        }
        Objects.requireNonNull(payload, "event payload is required");
        text = Objects.requireNonNull(text, "event text is required: the producer states a key and its arguments");
        fold = fold == null ? AXEventFold.NONE : fold;
        sourceId = sourceId == null || sourceId.isBlank() ? UNKNOWN_SOURCE : sourceId.trim();
    }

    /**
     * Submits an event whose tier comes from the built-in table and whose occurrences stay separate.
     *
     * @param text the template and arguments that word this event; the layer renders it
     */
    public static AXEventDescriptor of(String kind, String sourceId, AXEventPayload payload, AXEventText text) {
        return new AXEventDescriptor(kind, null, false, payload, text, AXEventFold.NONE, sourceId);
    }

    /** Submits an event that lets the layer fold its occurrences into one sentence of this shape. */
    public static AXEventDescriptor of(String kind, String sourceId, AXEventPayload payload, AXEventText text,
                                       AXEventFold fold) {
        return new AXEventDescriptor(kind, null, false, payload, text, fold, sourceId);
    }

    /** Same event, declaring its own tier instead of reading the built-in table. */
    public AXEventDescriptor withTier(AXEventTier tier) {
        return new AXEventDescriptor(kind, tier, suppress, payload, text, fold, sourceId);
    }
}
