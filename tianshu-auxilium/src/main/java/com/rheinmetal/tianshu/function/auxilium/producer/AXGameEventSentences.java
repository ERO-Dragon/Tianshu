package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.function.auxilium.event.AXEventAggregateRenderer;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDescriptor;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventPayload;

import java.util.Optional;

/**
 * Turns a captured signal into the descriptor this producer submits.
 *
 * <p>This is the producer half of the event design: it owns the kind catalog, the language keys and the
 * fold shapes, and it hands the event layer a sentence that is already worded. The AX side stores that
 * sentence as it is, so everything the model later reads was rendered here, from this producer's own
 * language resources.
 *
 * <p>The detail a sentence needs is resolved here too: a numeric metric becomes a clause such as
 * {@code lost 3 health}, a dimension becomes its display name and a death reuses the vanilla death
 * message. A missing detail key falls back to the plain value (the number, or the identifier the game
 * reported) and never to a language key, while a missing line template means this producer has no
 * sentence for the kind at all, so the signal is dropped instead of leaking a bare fragment into the
 * history.
 *
 * <p>The fold renderer is producer owned as well: a kind that carries a numeric metric folds into a
 * range sentence, a kind without one folds into a count sentence, and a kind that only happens once
 * declares no renderer at all, which makes the layer keep its occurrences separate.
 */
public final class AXGameEventSentences {
    /** Recorded with every submission as provenance; it never selects a code path in the AX layer. */
    public static final String SOURCE_ID = "tianshu.game_events";

    private static final String LINE_PREFIX = "tianshu.ax.event.line.";
    private static final String DETAIL_PREFIX = "tianshu.ax.event.detail.";
    private static final String DIMENSION_PREFIX = DETAIL_PREFIX + "dimension.";
    private static final String AGGREGATE_PREFIX = "tianshu.ax.event.aggregate.";
    private static final String BIOME_KEY_PREFIX = "biome.";
    private static final char NAMESPACE_SEPARATOR = ':';
    private static final char KEY_SEPARATOR = '.';

    /** Shape of the sentence a folded window becomes; the AX layer never guesses it. */
    private enum Fold {
        /** The kind is a one-off moment: its occurrences stay separate entries. */
        NONE,
        /** The folded sentence takes the occurrence count only. */
        COUNT,
        /** The folded sentence takes the count plus the first and last numeric metric. */
        RANGE
    }

    private final AXGameEventTextProvider texts;

    public AXGameEventSentences(AXGameEventTextProvider texts) {
        this.texts = texts == null ? AXGameEventTextProvider.NONE : texts;
    }

    /**
     * Renders the sentence of one signal and wraps it in the descriptor the event layer accepts.
     *
     * @param signal       crossing detected by the probe
     * @param sample       the reading the signal came from, used for the values the signal omits
     * @param deathMessage already localized vanilla death message, blank when the game had none
     * @return the descriptor, or empty when this producer cannot word the signal
     */
    public Optional<AXEventDescriptor> describe(AXGameEventSignal signal, AXGameEventSample sample,
                                               String deathMessage) {
        if (signal == null || sample == null) {
            return Optional.empty();
        }
        String detail = detailOf(signal, sample, deathMessage);
        String line = line(signal.kind(), detail);
        if (line.isBlank()) {
            return Optional.empty();
        }
        AXEventPayload payload = AXEventPayload.ofMagnitude(magnitudeOf(signal, sample));
        return Optional.of(descriptor(signal.kind(), payload, line));
    }

    private AXEventDescriptor descriptor(String kind, AXEventPayload payload, String line) {
        String aggregateKey = AGGREGATE_PREFIX + key(kind);
        return switch (foldOf(kind)) {
            case NONE -> AXEventDescriptor.of(kind, SOURCE_ID, payload, line);
            case COUNT -> AXEventDescriptor.of(kind, SOURCE_ID, payload, line,
                    (count, first, last) -> texts.text(aggregateKey, count));
            case RANGE -> AXEventDescriptor.of(kind, SOURCE_ID, payload, line,
                    (count, first, last) -> texts.text(aggregateKey, count, valueOf(first), valueOf(last)));
        };
    }

    /** The full sentence; a kind whose line template is missing has no sentence and is not submitted. */
    private String line(String kind, String detail) {
        return texts.text(LINE_PREFIX + key(kind), detail);
    }

    private String detailOf(AXGameEventSignal signal, AXGameEventSample sample, String deathMessage) {
        return switch (signal.kind()) {
            case AXGameEventKinds.HEALTH_CRITICAL -> valueDetail("health_critical", signal.value());
            case AXGameEventKinds.HEALTH_CHANGE -> valueDetail("health_change", signal.value());
            case AXGameEventKinds.HUNGER_CRITICAL -> valueDetail("hunger_critical", signal.value());
            case AXGameEventKinds.HUNGER_CHANGE -> valueDetail("hunger_change", signal.value());
            case AXGameEventKinds.DIMENSION_ENTER, AXGameEventKinds.RESPAWN -> dimensionDetail(sample.dimensionId());
            case AXGameEventKinds.BIOME_CHANGE -> biomeDetail(sample.biomeId());
            case AXGameEventKinds.DEATH -> deathDetail(deathMessage, sample.dimensionId());
            default -> "";
        };
    }

    /** Numeric metric handed to the fold renderer; {@code null} for kinds that carry none. */
    private Integer magnitudeOf(AXGameEventSignal signal, AXGameEventSample sample) {
        return switch (signal.kind()) {
            // A health drop reports the amount lost, but a folded health window is worth reading as
            // the health that was left after each drop, so it carries the current points instead.
            case AXGameEventKinds.HEALTH_CHANGE -> Integer.valueOf(Math.max(0, sample.healthPoints()));
            case AXGameEventKinds.HEALTH_CRITICAL,
                 AXGameEventKinds.HUNGER_CRITICAL,
                 AXGameEventKinds.HUNGER_CHANGE -> Integer.valueOf(signal.value());
            default -> null;
        };
    }

    private Fold foldOf(String kind) {
        return switch (kind) {
            case AXGameEventKinds.HEALTH_CHANGE,
                 AXGameEventKinds.HUNGER_CRITICAL,
                 AXGameEventKinds.HUNGER_CHANGE -> Fold.RANGE;
            case AXGameEventKinds.RESPAWN -> Fold.COUNT;
            default -> Fold.NONE;
        };
    }

    private String valueDetail(String segment, int value) {
        String rendered = texts.text(DETAIL_PREFIX + segment, value);
        return rendered.isBlank() ? Integer.toString(value) : rendered;
    }

    private String dimensionDetail(String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return "";
        }
        String rendered = texts.text(DIMENSION_PREFIX + key(dimensionId));
        return rendered.isBlank() ? dimensionId : rendered;
    }

    private String biomeDetail(String biomeId) {
        if (biomeId == null || biomeId.isBlank()) {
            return "";
        }
        String rendered = texts.text(BIOME_KEY_PREFIX + key(biomeId));
        return rendered.isBlank() ? biomeId : rendered;
    }

    private String deathDetail(String deathMessage, String dimensionId) {
        String trimmed = deathMessage == null ? "" : deathMessage.trim();
        return trimmed.isEmpty() ? dimensionDetail(dimensionId) : trimmed;
    }

    /** {@code tianshu:health_change} becomes the {@code tianshu.health_change} key segment. */
    private static String key(String namespacedId) {
        return namespacedId.trim().replace(NAMESPACE_SEPARATOR, KEY_SEPARATOR).replace('/', KEY_SEPARATOR);
    }

    private static String valueOf(Integer value) {
        return value == null ? "" : value.toString();
    }
}
