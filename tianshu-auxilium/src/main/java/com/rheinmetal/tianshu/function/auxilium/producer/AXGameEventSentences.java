package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.function.auxilium.event.AXEventDescriptor;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventFold;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventPayload;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventText;

import java.util.List;
import java.util.Optional;

/**
 * Turns a captured signal into the descriptor this producer submits — semantics only.
 *
 * <p>This class used to render the sentence as well, which is exactly what made every producer carry its
 * own language handling. Now it owns the <em>catalog</em>: which kind maps to which template key, which
 * detail template its argument needs, and what shape a folded window takes. It renders nothing and holds
 * no language table: the sentence is produced once, on the receiving side, through the injected text
 * provider.
 *
 * <p>An argument is a {@link AXEventText.Arg.Value} when the layer should substitute it verbatim (a number,
 * an already localized vanilla death message) and an {@link AXEventText.Arg.Nested} when it is itself a
 * template (a health clause, a dimension name), so even the fragments stay out of this class.
 */
public final class AXGameEventSentences {
    /** Recorded with every submission as provenance; it never selects a code path in the AX layer. */
    public static final String SOURCE_ID = "tianshu.game_events";

    private static final String LINE_PREFIX = "tianshu.ax.event.line.";
    private static final String DETAIL_PREFIX = "tianshu.ax.event.detail.";
    private static final String DIMENSION_PREFIX = DETAIL_PREFIX + "dimension.";
    private static final String BIOME_KEY_PREFIX = "biome.";
    private static final char NAMESPACE_SEPARATOR = ':';
    private static final char KEY_SEPARATOR = '.';

    /**
     * Builds the descriptor of one signal.
     *
     * @param signal       crossing detected by the probe
     * @param sample       the reading the signal came from, used for the values the signal omits
     * @param deathMessage already localized vanilla death message, blank when the game had none
     * @return the descriptor, or empty when there is nothing to submit
     */
    public Optional<AXEventDescriptor> describe(AXGameEventSignal signal, AXGameEventSample sample,
                                                String deathMessage) {
        if (signal == null || sample == null) {
            return Optional.empty();
        }
        AXEventText.Arg detail = detailOf(signal, sample, deathMessage);
        String lineKey = LINE_PREFIX + key(signal.kind());
        AXEventText line = detail == null
                ? AXEventText.of(lineKey)
                : AXEventText.of(lineKey, List.of(detail));
        AXEventPayload payload = AXEventPayload.ofMagnitude(magnitudeOf(signal, sample));
        return Optional.of(AXEventDescriptor.of(signal.kind(), SOURCE_ID, payload, line, foldOf(signal.kind())));
    }

    /**
     * The single argument the line template takes, as a value or as another template.
     *
     * @return the argument, or {@code null} when the kind's sentence takes none
     */
    private AXEventText.Arg detailOf(AXGameEventSignal signal, AXGameEventSample sample, String deathMessage) {
        return switch (signal.kind()) {
            case AXGameEventKinds.HEALTH_CRITICAL -> valueDetail("health_critical", signal.value());
            case AXGameEventKinds.HEALTH_CHANGE -> valueDetail("health_change", signal.value());
            case AXGameEventKinds.HUNGER_CRITICAL -> valueDetail("hunger_critical", signal.value());
            case AXGameEventKinds.HUNGER_CHANGE -> valueDetail("hunger_change", signal.value());
            case AXGameEventKinds.DIMENSION_ENTER, AXGameEventKinds.RESPAWN -> dimensionDetail(sample.dimensionId());
            case AXGameEventKinds.BIOME_CHANGE -> biomeDetail(sample.biomeId());
            case AXGameEventKinds.DEATH -> deathDetail(deathMessage, sample.dimensionId());
            default -> null;
        };
    }

    /** Numeric metric handed to the fold template; {@code null} for kinds that carry none. */
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

    private AXEventFold foldOf(String kind) {
        return switch (kind) {
            case AXGameEventKinds.HEALTH_CHANGE,
                 AXGameEventKinds.HUNGER_CRITICAL,
                 AXGameEventKinds.HUNGER_CHANGE -> AXEventFold.RANGE;
            case AXGameEventKinds.RESPAWN -> AXEventFold.COUNT;
            default -> AXEventFold.NONE;
        };
    }

    private AXEventText.Arg valueDetail(String segment, int value) {
        return AXEventText.Arg.nested(AXEventText.of(DETAIL_PREFIX + segment, Integer.toString(value)));
    }

    private AXEventText.Arg dimensionDetail(String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return null;
        }
        return AXEventText.Arg.nested(AXEventText.of(DIMENSION_PREFIX + key(dimensionId)));
    }

    private AXEventText.Arg biomeDetail(String biomeId) {
        if (biomeId == null || biomeId.isBlank()) {
            return null;
        }
        return AXEventText.Arg.nested(AXEventText.of(BIOME_KEY_PREFIX + key(biomeId)));
    }

    private AXEventText.Arg deathDetail(String deathMessage, String dimensionId) {
        String trimmed = deathMessage == null ? "" : deathMessage.trim();
        return trimmed.isEmpty() ? dimensionDetail(dimensionId) : AXEventText.Arg.value(trimmed);
    }

    /** {@code tianshu:health_change} becomes the {@code tianshu.health_change} key segment. */
    private static String key(String namespacedId) {
        return namespacedId.trim().replace(NAMESPACE_SEPARATOR, KEY_SEPARATOR).replace('/', KEY_SEPARATOR);
    }
}
