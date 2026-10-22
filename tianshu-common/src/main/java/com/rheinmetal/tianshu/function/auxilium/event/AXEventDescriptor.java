package com.rheinmetal.tianshu.function.auxilium.event;

import java.text.Normalizer;
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
 * <p>{@code text} is the sentence the producer already rendered from its own language resources. The
 * layer stores that string verbatim (after the sanitation below) and never assembles wording of its
 * own, which is also why there is no "language key plus arguments" handle in this layer: a producer
 * that cannot render its own sentence must not submit an event.
 *
 * <p>{@code aggregateRenderer} is optional. While a window is open the layer folds occurrences and
 * calls back into the producer for the folded sentence; without a renderer the occurrences stay
 * separate entries, so a missing renderer degrades to more entries and never to generated text.
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
        String text,
        AXEventAggregateRenderer aggregateRenderer,
        String sourceId
) {
    /** Namespace plus local name, lowercase, colon separated; see kind discipline in the design doc. */
    private static final Pattern KIND_PATTERN = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** Source recorded when a producer does not name one. */
    public static final String UNKNOWN_SOURCE = "unknown";

    /**
     * Upper bound for {@link #text}.
     *
     * <p>Text comes from an arbitrary producer module, so one history line has to stay bounded; the
     * cap is generous enough for a rendered sentence and its arguments.
     */
    public static final int MAX_TEXT_CHARS = 120;

    public AXEventDescriptor {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("event kind is required");
        }
        kind = kind.trim();
        if (!KIND_PATTERN.matcher(kind).matches()) {
            throw new IllegalArgumentException("event kind must be namespaced, for example tianshu:death: " + kind);
        }
        Objects.requireNonNull(payload, "event payload is required");
        text = sanitize(text);
        sourceId = sourceId == null || sourceId.isBlank() ? UNKNOWN_SOURCE : sourceId.trim();
    }

    /**
     * Submits an event whose tier comes from the built-in table and whose occurrences stay separate.
     *
     * @param text the sentence the producer already rendered; required and never blank
     */
    public static AXEventDescriptor of(String kind, String sourceId, AXEventPayload payload, String text) {
        return new AXEventDescriptor(kind, null, false, payload, text, null, sourceId);
    }

    /** Submits an event that lets the layer fold its occurrences through the producer's renderer. */
    public static AXEventDescriptor of(String kind, String sourceId, AXEventPayload payload, String text,
                                       AXEventAggregateRenderer aggregateRenderer) {
        return new AXEventDescriptor(kind, null, false, payload, text, aggregateRenderer, sourceId);
    }

    /** Same event, declaring its own tier instead of reading the built-in table. */
    public AXEventDescriptor withTier(AXEventTier tier) {
        return new AXEventDescriptor(kind, tier, suppress, payload, text, aggregateRenderer, sourceId);
    }

    /**
     * Treats producer supplied text as untrusted input: NFC normalized, stripped of angle brackets (a
     * module must not be able to inject {@code <event>} or any other prompt tag through an event
     * line) and truncated on a word boundary so one history line stays bounded.
     */
    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("event text is required: the producer must render it");
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).trim();
        StringBuilder stripped = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch != '<' && ch != '>') {
                stripped.append(ch);
            }
        }
        String cleaned = stripped.toString().trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("event text is required: the producer must render it");
        }
        return truncate(cleaned);
    }

    /**
     * Truncates on the last separator before the cap so a word or clause is not cut in half, and
     * never splits a surrogate pair.
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
