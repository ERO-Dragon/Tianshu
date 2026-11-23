package com.rheinmetal.tianshu.dubhe.shared.event;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One event submitted to the layer, exactly as a module hands it over.
 *
 * <p>The descriptor is a module agnostic contract: any module may submit any namespaced kind and the layer treats
 * every submission the same way. {@code sourceId} is provenance only - it is recorded with the entry and never
 * reaches a decision branch.
 *
 * <p>{@code text} is the event's wording <em>as semantics</em>: a template key plus its arguments
 * ({@link DubheEventText}). The producer never words a sentence; the layer renders it once through the language table it
 * was given, so every producer shares one rendering path instead of implementing one. The rendered sentence is what
 * reaches the history and the voice channel.
 *
 * <p>{@code fold} is how a closed fold window is worded ({@link DubheEventFold}). {@link DubheEventFold#NONE} keeps
 * occurrences separate, which is the shape for a one-off moment.
 *
 * <p>{@code tier} is optional: when it is {@code null} the dispatcher consults the built-in tier table and falls back
 * to the most conservative tier when the kind is unknown.
 *
 * <p>{@link #highlight()} is the producer's own, per-occurrence opinion that this event is worth a step up the
 * ladder — a dynamic whitelist. It <em>takes precedence over the static list</em>, because the module is the one that
 * knows whether this particular occurrence matters; the static list is the player's standing preference, and a
 * producer that marks everything gets pulled back by the frequency step rather than trusted blindly.
 * {@code suppress} is the opposite direction and only registers as a blacklist flag when {@code highlight} is not set.
 */
public record DubheEventDescriptor(
        String kind,
        DubheEventTier tier,
        boolean suppress,
        boolean highlight,
        DubheEventPayload payload,
        DubheEventText text,
        DubheEventFold fold,
        String sourceId
) {
    /** Namespace plus local name, lowercase, colon separated; see kind discipline in the design doc. */
    private static final Pattern KIND_PATTERN = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** Source recorded when a producer does not name one. */
    public static final String UNKNOWN_SOURCE = "unknown";

    public DubheEventDescriptor {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("event kind is required");
        }
        kind = kind.trim();
        if (!KIND_PATTERN.matcher(kind).matches()) {
            throw new IllegalArgumentException("event kind must be namespaced, for example tianshu:death: " + kind);
        }
        Objects.requireNonNull(payload, "event payload is required");
        text = Objects.requireNonNull(text, "event text is required: the producer states a key and its arguments");
        fold = fold == null ? DubheEventFold.NONE : fold;
        sourceId = sourceId == null || sourceId.isBlank() ? UNKNOWN_SOURCE : sourceId.trim();
    }

    /**
     * Submits an event whose tier comes from the built-in table and whose occurrences stay separate.
     *
     * @param text the template and arguments that word this event; the layer renders it
     */
    public static DubheEventDescriptor of(String kind, String sourceId, DubheEventPayload payload, DubheEventText text) {
        return new DubheEventDescriptor(kind, null, false, false, payload, text, DubheEventFold.NONE, sourceId);
    }

    /** Submits an event that lets the layer fold its occurrences into one sentence of this shape. */
    public static DubheEventDescriptor of(String kind, String sourceId, DubheEventPayload payload, DubheEventText text,
                                       DubheEventFold fold) {
        return new DubheEventDescriptor(kind, null, false, false, payload, text, fold, sourceId);
    }

    /** Same event, declaring its own tier instead of reading the built-in table. */
    public DubheEventDescriptor withTier(DubheEventTier tier) {
        return new DubheEventDescriptor(kind, tier, suppress, highlight, payload, text, fold, sourceId);
    }

    /** Same event, marked or unmarked as worth a step up the matrix by its producer. */
    public DubheEventDescriptor withHighlight(boolean highlight) {
        return new DubheEventDescriptor(kind, tier, suppress, highlight, payload, text, fold, sourceId);
    }

    /** Same event, marked or unmarked as deliberately weakened by its producer. */
    public DubheEventDescriptor withSuppress(boolean suppress) {
        return new DubheEventDescriptor(kind, tier, suppress, highlight, payload, text, fold, sourceId);
    }

    /**
     * An event whose wording the producer rendered itself, so this module never renders it.
     *
     * <p>{@code text} is left blank on purpose: there is no template, and the sentence is passed
     * straight to {@code DubheGameEventService#submitRendered}, which only sanitizes it. A blank template
     * also means there is nothing to fold, so {@code fold} is fixed at {@link DubheEventFold#NONE} and
     * every occurrence stays its own history line.</p>
     *
     * <p>Used by the event entry external modules submit to ({@code AX.EVENT_INPUT}): they own their own
     * language files, and this module holds no wording of theirs.</p>
     */
    public static DubheEventDescriptor rendered(String kind, DubheEventTier tier, String sourceId) {
        return new DubheEventDescriptor(kind, tier, false, false, DubheEventPayload.of(), DubheEventText.of(""),
                DubheEventFold.NONE, sourceId);
    }
}
